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
from pydantic import BaseModel, Field

from .backends import BackendRouter, BackendSession, FixtureRunSummary
from .controller_contract import CreateMediaSessionRequest, MediaSession, StartMediaSessionRequest, StopMediaSessionRequest
from .live_rtp import LiveRtpHooks
from .rtp import RtpInboundTelemetryTracker
from .runtimes import RuntimeConfigurationError, RuntimeSessionTelemetry


class TelemetryPayload(BaseModel):
    runtime: str = "openai-realtime"
    packet_loss_pct: float | None = None
    jitter_ms: float | None = None
    ws_reconnects: int = 0
    ws_errors: int = 0
    first_audio_latency_ms: float | None = None


class RunFixtureRequest(BaseModel):
    fixture_path: str | None = Field(
        default=None,
        description="Optional absolute or working-directory-relative path to a mono 16-bit PCM WAV file.",
    )
    output_wav_path: str | None = Field(
        default=None,
        description="Optional output path for the model's generated audio. Defaults to the media bridge artifact directory.",
    )
    timeout_seconds: float = Field(default=45.0, ge=1.0, le=300.0)
    include_greeting: bool = Field(
        default=False,
        description="When true, the bridge asks the model to speak its configured greeting before handling the fixture audio.",
    )


class FixtureRunResponse(BaseModel):
    session_id: str
    call_id: str
    runtime: str
    fixture_path: str | None
    output_wav_path: str
    output_sample_rate_hz: int
    input_duration_ms: float
    output_duration_ms: float
    first_audio_latency_ms: float | None = None
    input_transcript: str | None = None
    output_transcript: str | None = None
    vendor_session_id: str | None = None


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


app = FastAPI(title="MIMIR Media Bridge", version="2.2.0")
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


def _to_media_session(record: BackendSession) -> MediaSession:
    return MediaSession(
        session_id=record.session_id,
        bridge_session_id=record.session_id,
        call_id=record.call_id,
        status=record.status,
        reason=record.reason,
        rtp=record.rtp,
    )


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


def _live_rtp_hooks(record: BackendSession) -> LiveRtpHooks:
    async def on_first_audio(first_audio_latency_ms: float) -> None:
        if record.first_audio_at is None:
            record.first_audio_at = time.time()
            FIRST_AUDIO_LATENCY_SECONDS.labels(runtime=record.runtime).observe(first_audio_latency_ms / 1000.0)
            await _emit_event("media.first_audio", record, {"first_audio_latency_ms": first_audio_latency_ms})

    async def on_telemetry(tracker: RtpInboundTelemetryTracker, runtime_telemetry: RuntimeSessionTelemetry) -> None:
        snapshot = tracker.snapshot()
        if snapshot.packet_loss_pct:
            RTP_PACKET_LOSS_PCT.labels(runtime=record.runtime).observe(snapshot.packet_loss_pct)
        if snapshot.jitter_ms:
            RTP_JITTER_MS.labels(runtime=record.runtime).observe(snapshot.jitter_ms)
        if runtime_telemetry.ws_reconnects:
            WS_RECONNECT_TOTAL.labels(runtime=record.runtime).inc(runtime_telemetry.ws_reconnects)
        if runtime_telemetry.ws_errors:
            WS_ERROR_TOTAL.labels(runtime=record.runtime).inc(runtime_telemetry.ws_errors)

        await _emit_event(
            "media.telemetry",
            record,
            {
                "packet_loss_pct": snapshot.packet_loss_pct,
                "jitter_ms": snapshot.jitter_ms,
                "received_packets": snapshot.received_packets,
                "invalid_packets": snapshot.invalid_packets,
                "ws_reconnects": runtime_telemetry.ws_reconnects,
                "ws_errors": runtime_telemetry.ws_errors,
                "first_audio_latency_ms": None if record.first_audio_at is None else round((record.first_audio_at - record.created_at.timestamp()) * 1000.0, 2),
                "vendor_session_id": runtime_telemetry.vendor_session_id,
            },
        )

    async def on_failure(reason: str, tracker: RtpInboundTelemetryTracker, runtime_telemetry: RuntimeSessionTelemetry) -> None:
        if record.status == "terminated":
            return
        record.status = "terminated"
        record.reason = reason
        CALL_COMPLETION_TOTAL.labels(result="failed", reason="live_bridge_failure", runtime=record.runtime).inc()
        await _emit_event(
            "media.session.failed",
            record,
            {
                "reason": reason,
                "packet_loss_pct": tracker.snapshot().packet_loss_pct,
                "jitter_ms": tracker.snapshot().jitter_ms,
                "ws_errors": runtime_telemetry.ws_errors,
                "vendor_session_id": runtime_telemetry.vendor_session_id,
            },
        )

    return LiveRtpHooks(
        on_first_audio=on_first_audio,
        on_telemetry=on_telemetry,
        on_failure=on_failure,
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

    try:
        backend = backend_router.choose_backend(requested_runtime=requested_runtime, model_name=request.ai_profile.model_name)
    except RuntimeConfigurationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

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
            "local_rtp": f"{record.rtp.local_address}:{record.rtp.local_port}",
            "remote_rtp": f"{record.rtp.remote_address}:{record.rtp.remote_port}",
        },
    )
    return _to_media_session(record)


@app.post("/v1/media/sessions/{session_id}/start", response_model=MediaSession)
async def start_media_session(
    session_id: str,
    body: StartMediaSessionRequest | None = None,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
) -> MediaSession:
    key = f"attach_media:{session_id}:{idempotency_key}"
    if key in _idempotency:
        return MediaSession(**_idempotency[key])

    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")

    if record.status == "terminated":
        response = _to_media_session(record)
        _idempotency[key] = response.model_dump()
        return response

    if record.status != "active":
        backend = backend_router.choose_backend(requested_runtime=record.runtime, model_name=record.request.ai_profile.model_name)
        try:
            await backend.start(
                record,
                live=True,
                hooks=_live_rtp_hooks(record),
                remote_rtp=body.remote_rtp if body else None,
            )
        except RuntimeConfigurationError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except RuntimeError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        await _emit_event(
            "media.session.active",
            record,
            {
                "local_rtp": f"{record.rtp.local_address}:{record.rtp.local_port}",
                "remote_rtp": f"{record.rtp.remote_address}:{record.rtp.remote_port}",
            },
        )

    response = _to_media_session(record)
    _idempotency[key] = response.model_dump()
    return response


@app.post("/v1/media/sessions/{session_id}/fixtures/run", response_model=FixtureRunResponse)
async def run_media_fixture(session_id: str, body: RunFixtureRequest) -> FixtureRunResponse:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")

    if record.status == "terminated":
        raise HTTPException(status_code=409, detail="media session is terminated")

    if record.status != "active":
        backend = backend_router.choose_backend(requested_runtime=record.runtime, model_name=record.request.ai_profile.model_name)
        await backend.start(record, live=False)
        await _emit_event("media.session.active", record)

    backend = backend_router.choose_backend(requested_runtime=record.runtime, model_name=record.request.ai_profile.model_name)

    try:
        summary = await backend.run_fixture(
            session=record,
            fixture_path=body.fixture_path,
            output_wav_path=body.output_wav_path,
            timeout_seconds=body.timeout_seconds,
            include_greeting=body.include_greeting,
        )
    except RuntimeConfigurationError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except FileNotFoundError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except TimeoutError as exc:
        CALL_COMPLETION_TOTAL.labels(result="failed", reason="fixture_timeout", runtime=record.runtime).inc()
        WS_ERROR_TOTAL.labels(runtime=record.runtime).inc()
        await _emit_event("media.fixture.failed", record, {"reason": "timeout", "message": str(exc)})
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except RuntimeError as exc:
        CALL_COMPLETION_TOTAL.labels(result="failed", reason="fixture_runtime_error", runtime=record.runtime).inc()
        WS_ERROR_TOTAL.labels(runtime=record.runtime).inc()
        await _emit_event("media.fixture.failed", record, {"reason": "runtime_error", "message": str(exc)})
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    if record.first_audio_at is None and summary.first_audio_latency_ms is not None:
        record.first_audio_at = time.time()
        FIRST_AUDIO_LATENCY_SECONDS.labels(runtime=record.runtime).observe(summary.first_audio_latency_ms / 1000.0)
        await _emit_event("media.first_audio", record, {"first_audio_latency_ms": summary.first_audio_latency_ms})

    await _emit_event(
        "media.telemetry",
        record,
        {
            "first_audio_latency_ms": summary.first_audio_latency_ms,
            "fixture_input_duration_ms": summary.input_duration_ms,
            "fixture_output_duration_ms": summary.output_duration_ms,
        },
    )
    await _emit_event(
        "media.fixture.completed",
        record,
        _fixture_summary_attributes(summary),
    )

    return FixtureRunResponse(
        session_id=record.session_id,
        call_id=record.call_id,
        runtime=record.runtime,
        fixture_path=summary.fixture_path,
        output_wav_path=summary.output_wav_path,
        output_sample_rate_hz=summary.output_sample_rate_hz,
        input_duration_ms=summary.input_duration_ms,
        output_duration_ms=summary.output_duration_ms,
        first_audio_latency_ms=summary.first_audio_latency_ms,
        input_transcript=summary.input_transcript,
        output_transcript=summary.output_transcript,
        vendor_session_id=summary.vendor_session_id,
    )


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

    backend = backend_router.choose_backend(requested_runtime=record.runtime, model_name=record.request.ai_profile.model_name)
    await backend.stop(record, reason=body.reason)
    await _emit_event("media.session.ended", record, {"reason": body.reason})
    CALL_COMPLETION_TOTAL.labels(result="ended", reason=body.reason, runtime=record.runtime).inc()
    response = _to_media_session(record)
    _idempotency[key] = response.model_dump()
    return response


@app.get("/v1/media/sessions/{session_id}", response_model=MediaSession)
async def get_media_session(session_id: str) -> MediaSession:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")
    return _to_media_session(record)


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
async def health() -> dict[str, Any]:
    return {"status": "ok", "service": "media-bridge", "runtimes": backend_router.available_runtimes()}


@app.get("/metrics")
async def metrics() -> PlainTextResponse:
    return PlainTextResponse(generate_latest().decode("utf-8"), media_type=CONTENT_TYPE_LATEST)


def _fixture_summary_attributes(summary: FixtureRunSummary) -> dict[str, Any]:
    return {
        "fixture_path": summary.fixture_path,
        "output_wav_path": summary.output_wav_path,
        "output_sample_rate_hz": summary.output_sample_rate_hz,
        "input_duration_ms": summary.input_duration_ms,
        "output_duration_ms": summary.output_duration_ms,
        "first_audio_latency_ms": summary.first_audio_latency_ms,
        "vendor_session_id": summary.vendor_session_id,
        "input_transcript": summary.input_transcript,
        "output_transcript": summary.output_transcript,
    }
