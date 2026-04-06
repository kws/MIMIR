from __future__ import annotations

import asyncio
import json
import time
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import PlainTextResponse, StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel

from .backends import BackendRouter, BackendSession
from .controller_contract import CreateMediaSessionRequest, MediaSession, StopMediaSessionRequest


class TelemetryPayload(BaseModel):
    runtime: str = "python"
    packet_loss_pct: float | None = None
    jitter_ms: float | None = None
    ws_reconnects: int = 0
    ws_errors: int = 0
    first_audio_latency_ms: float | None = None


class EventBus:
    def __init__(self) -> None:
        self._subscribers: set[asyncio.Queue[dict[str, Any]]] = set()

    def subscribe(self) -> asyncio.Queue[dict[str, Any]]:
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._subscribers.add(queue)
        return queue

    def unsubscribe(self, queue: asyncio.Queue[dict[str, Any]]) -> None:
        self._subscribers.discard(queue)

    async def publish(self, event: dict[str, Any]) -> None:
        for queue in list(self._subscribers):
            await queue.put(event)


app = FastAPI(title="MIMIR Media Bridge", version="2.0.0")
_sessions: dict[str, BackendSession] = {}
_event_bus = EventBus()
_idempotency: dict[str, dict[str, Any]] = {}
backend_router = BackendRouter()

RTP_PACKET_LOSS_PCT = Histogram(
    "media_bridge_rtp_packet_loss_pct",
    "Observed RTP packet loss percentage.",
    labelnames=("runtime",),
    buckets=(0, 0.1, 0.5, 1, 2, 5, 10, 20, 40, 80, 100),
)
RTP_JITTER_MS = Histogram(
    "media_bridge_rtp_jitter_ms",
    "Observed RTP jitter in milliseconds.",
    labelnames=("runtime",),
    buckets=(1, 2, 5, 10, 20, 30, 50, 75, 100, 200),
)
WS_RECONNECT_TOTAL = Counter(
    "media_bridge_websocket_reconnect_total",
    "Count of websocket reconnect attempts in media runtime.",
    labelnames=("runtime",),
)
WS_ERROR_TOTAL = Counter(
    "media_bridge_websocket_error_total",
    "Count of websocket errors in media runtime.",
    labelnames=("runtime",),
)
FIRST_AUDIO_LATENCY_SECONDS = Histogram(
    "media_bridge_first_audio_latency_seconds",
    "Time from media session creation to first audio.",
    labelnames=("runtime",),
    buckets=(0.1, 0.25, 0.5, 1, 2, 3, 5, 10, 20, 30),
)
CALL_COMPLETION_TOTAL = Counter(
    "media_bridge_call_completion_total",
    "Call completion and failure reasons observed by media bridge.",
    labelnames=("result", "reason", "runtime"),
)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


async def _emit_event(event_type: str, record: BackendSession, attributes: dict[str, Any] | None = None) -> None:
    await _event_bus.publish(
        {
            "event_id": str(uuid.uuid4()),
            "event_type": event_type,
            "media_session_id": record.session_id,
            "bridge_session_id": record.session_id,
            "call_id": record.call_id,
            "occurred_at": _now(),
            "attributes": {"runtime": record.runtime, **(attributes or {})},
        }
    )


@app.post("/v1/media/sessions", response_model=MediaSession, status_code=201)
async def create_media_session(request: CreateMediaSessionRequest) -> MediaSession:
    if request.direction.lower() != "inbound":
        raise HTTPException(
            status_code=403,
            detail={
                "error_code": "MEDIA_ALLOCATION_REQUIRES_INBOUND_DIRECTION",
                "message": "Media allocation is allowed only for inbound calls.",
            },
        )

    session_id = f"media-{uuid.uuid4()}"
    requested_runtime = request.metadata.get("bridge_runtime")
    backend = backend_router.choose_backend(request.call_id, requested_runtime=requested_runtime)
    record = backend.create(request, session_id=session_id)
    _sessions[session_id] = record

    await _emit_event(
        "media.session.created",
        record,
        {
            "called_extension": request.participant.called_extension,
            "input_codec": request.media_settings.input_codec,
            "output_codec": request.media_settings.output_codec,
            "model_name": request.ai_profile.model_name,
            "voice": request.ai_profile.voice,
            "vad_mode": request.ai_profile.vad_mode,
            "local_rtp": f"{request.rtp.local_address}:{request.rtp.local_port}",
            "remote_rtp": f"{request.rtp.remote_address}:{request.rtp.remote_port}",
        },
    )
    return MediaSession(session_id=record.session_id, bridge_session_id=record.session_id, call_id=record.call_id, status=record.status)


@app.post("/v1/media/sessions/{session_id}/start", response_model=MediaSession)
async def start_media_session(session_id: str, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> MediaSession:
    key = f"attach_media:{session_id}:{idempotency_key}"
    if key in _idempotency:
        return MediaSession(**_idempotency[key])

    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")

    if record.status not in {"active", "terminated"}:
        backend = backend_router.choose_backend(record.call_id, requested_runtime=record.runtime)
        backend.start(record)
        first_audio_latency = max(0.0, time.time() - record.created_at.timestamp())
        record.first_audio_at = time.time()
        FIRST_AUDIO_LATENCY_SECONDS.labels(runtime=record.runtime).observe(first_audio_latency)
        await _emit_event("media.first_audio", record, {"first_audio_latency_ms": round(first_audio_latency * 1000, 2)})
        await _emit_event("media.session.active", record)

    response = MediaSession(
        session_id=record.session_id,
        bridge_session_id=record.session_id,
        call_id=record.call_id,
        status=record.status,
        reason=record.reason,
    )
    _idempotency[key] = response.model_dump()
    return response


@app.post("/v1/media/sessions/{session_id}/stop", response_model=MediaSession)
async def stop_media_session(
    session_id: str,
    body: StopMediaSessionRequest,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
) -> MediaSession:
    key = f"terminate_media:{session_id}:{idempotency_key}"
    if key in _idempotency:
        return MediaSession(**_idempotency[key])

    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")

    backend = backend_router.choose_backend(record.call_id, requested_runtime=record.runtime)
    backend.stop(record, reason=body.reason)
    await _emit_event("media.session.ended", record, {"reason": body.reason})
    CALL_COMPLETION_TOTAL.labels(result="ended", reason=body.reason, runtime=record.runtime).inc()
    response = MediaSession(
        session_id=record.session_id,
        bridge_session_id=record.session_id,
        call_id=record.call_id,
        status=record.status,
        reason=record.reason,
    )
    _idempotency[key] = response.model_dump()
    return response


@app.get("/v1/media/sessions/{session_id}", response_model=MediaSession)
async def get_media_session(session_id: str) -> MediaSession:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")
    return MediaSession(
        session_id=record.session_id,
        bridge_session_id=record.session_id,
        call_id=record.call_id,
        status=record.status,
        reason=record.reason,
    )


@app.post("/v1/media/sessions/{session_id}/telemetry")
async def post_session_telemetry(session_id: str, body: TelemetryPayload) -> dict[str, str]:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")

    record.runtime = body.runtime or record.runtime
    if body.packet_loss_pct is not None:
        RTP_PACKET_LOSS_PCT.labels(runtime=record.runtime).observe(body.packet_loss_pct)
    if body.jitter_ms is not None:
        RTP_JITTER_MS.labels(runtime=record.runtime).observe(body.jitter_ms)
    if body.ws_reconnects:
        WS_RECONNECT_TOTAL.labels(runtime=record.runtime).inc(body.ws_reconnects)
    if body.ws_errors:
        WS_ERROR_TOTAL.labels(runtime=record.runtime).inc(body.ws_errors)
    if body.first_audio_latency_ms is not None:
        FIRST_AUDIO_LATENCY_SECONDS.labels(runtime=record.runtime).observe(body.first_audio_latency_ms / 1000.0)
    if body.ws_errors:
        CALL_COMPLETION_TOTAL.labels(result="failed", reason="websocket_error", runtime=record.runtime).inc()

    await _emit_event(
        "media.telemetry",
        record,
        {
            "packet_loss_pct": body.packet_loss_pct,
            "jitter_ms": body.jitter_ms,
            "ws_reconnects": body.ws_reconnects,
            "ws_errors": body.ws_errors,
            "first_audio_latency_ms": body.first_audio_latency_ms,
        },
    )
    return {"status": "accepted"}


@app.get("/v1/media/events")
async def stream_media_events(
    session_id: str | None = Query(default=None, description="Optional comma-separated media session IDs"),
    call_id: str | None = Query(default=None, description="Optional call ID filter"),
) -> StreamingResponse:
    filter_ids = set(session_id.split(",")) if session_id else None
    queue = _event_bus.subscribe()

    async def event_generator():
        try:
            while True:
                event = await queue.get()
                if filter_ids and event["media_session_id"] not in filter_ids:
                    continue
                if call_id and event["call_id"] != call_id:
                    continue
                yield f"event: {event['event_type']}\n"
                yield f"data: {json.dumps(event)}\n\n"
        finally:
            _event_bus.unsubscribe(queue)

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.get("/healthz")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "media-bridge"}


@app.get("/metrics")
async def metrics() -> PlainTextResponse:
    return PlainTextResponse(generate_latest().decode("utf-8"), media_type=CONTENT_TYPE_LATEST)
