from __future__ import annotations

import asyncio
import json
import os
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field


class SipParticipant(BaseModel):
    caller: str
    callee: str
    called_extension: str


class RtpFlow(BaseModel):
    local_address: str
    local_port: int
    remote_address: str
    remote_port: int


class MediaSessionConfig(BaseModel):
    model: str
    voice: str
    instructions: str
    greeting: str
    input_codec: str = "g711_ulaw"
    output_codec: str = "g711_ulaw"
    sample_rate_hz: int = 8000


class CreateMediaSessionRequest(BaseModel):
    call_id: str
    participant: SipParticipant
    config: MediaSessionConfig
    rtp: RtpFlow
    metadata: dict[str, str] = Field(default_factory=dict)


class StopMediaSessionRequest(BaseModel):
    reason: str = "normal_clearing"


class MediaSession(BaseModel):
    session_id: str
    call_id: str
    status: str
    reason: str | None = None


@dataclass
class SessionRecord:
    session_id: str
    call_id: str
    status: str
    reason: str | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


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


app = FastAPI(title="MIMIR Media Bridge", version="1.1.0")
_sessions: dict[str, SessionRecord] = {}
_event_bus = EventBus()
_idempotency: dict[str, dict[str, Any]] = {}
_observed_call_ids: set[str] = set()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


async def _emit_event(event_type: str, record: SessionRecord, attributes: dict[str, Any] | None = None) -> None:
    await _event_bus.publish(
        {
            "event_id": str(uuid.uuid4()),
            "event_type": event_type,
            "media_session_id": record.session_id,
            "call_id": record.call_id,
            "occurred_at": _now(),
            "attributes": attributes or {},
        }
    )


async def _consume_sip_call_events() -> None:
    sip_url = os.getenv("SIP_FLOW_HANDLER_URL")
    if not sip_url:
        return
    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream("GET", f"{sip_url.rstrip('/')}/v1/call-events") as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if line.startswith("data: "):
                    payload = json.loads(line.replace("data: ", "", 1))
                    if payload.get("call_id"):
                        _observed_call_ids.add(payload["call_id"])


@app.on_event("startup")
async def startup() -> None:
    asyncio.create_task(_consume_sip_call_events())


@app.post("/v1/media/sessions", response_model=MediaSession, status_code=201)
async def create_media_session(request: CreateMediaSessionRequest) -> MediaSession:
    session_id = f"media-{uuid.uuid4()}"
    record = SessionRecord(session_id=session_id, call_id=request.call_id, status="created")
    _sessions[session_id] = record
    await _emit_event(
        "call.media_ready",
        record,
        {
            "called_extension": request.participant.called_extension,
            "input_codec": request.config.input_codec,
            "output_codec": request.config.output_codec,
            "local_rtp": f"{request.rtp.local_address}:{request.rtp.local_port}",
            "remote_rtp": f"{request.rtp.remote_address}:{request.rtp.remote_port}",
        },
    )
    return MediaSession(session_id=record.session_id, call_id=record.call_id, status=record.status)


@app.post("/v1/media/sessions/{session_id}/start", response_model=MediaSession)
async def start_media_session(session_id: str, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> MediaSession:
    key = f"attach_media:{session_id}:{idempotency_key}"
    if key in _idempotency:
        return MediaSession(**_idempotency[key])

    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")
    if record.status not in {"active", "terminated"}:
        record.status = "active"
        await _emit_event("call.answered", record)
        await _emit_event("call.active", record)

    response = MediaSession(session_id=record.session_id, call_id=record.call_id, status=record.status, reason=record.reason)
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
    record.status = "terminated"
    record.reason = body.reason
    await _emit_event("call.ended", record, {"reason": body.reason})
    response = MediaSession(session_id=record.session_id, call_id=record.call_id, status=record.status, reason=record.reason)
    _idempotency[key] = response.model_dump()
    return response


@app.get("/v1/media/sessions/{session_id}", response_model=MediaSession)
async def get_media_session(session_id: str) -> MediaSession:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")
    return MediaSession(
        session_id=record.session_id,
        call_id=record.call_id,
        status=record.status,
        reason=record.reason,
    )


@app.get("/v1/call-events")
async def stream_call_events(call_id: str | None = Query(default=None, description="Optional call_id filter")) -> StreamingResponse:
    queue = _event_bus.subscribe()

    async def event_generator():
        try:
            while True:
                event = await queue.get()
                if call_id and event["call_id"] != call_id:
                    continue
                yield f"event: {event['event_type']}\n"
                yield f"data: {json.dumps(event)}\n\n"
        finally:
            _event_bus.unsubscribe(queue)

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.get("/v1/media/events")
async def stream_media_events(session_id: str | None = Query(default=None, description="Comma separated media session IDs")) -> StreamingResponse:
    filter_ids = set(session_id.split(",")) if session_id else None
    queue = _event_bus.subscribe()

    async def event_generator():
        try:
            while True:
                event = await queue.get()
                if filter_ids and event["media_session_id"] not in filter_ids:
                    continue
                yield f"event: {event['event_type']}\n"
                yield f"data: {json.dumps(event)}\n\n"
        finally:
            _event_bus.unsubscribe(queue)

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.get("/healthz")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "media-bridge", "observed_calls": str(len(_observed_call_ids))}
