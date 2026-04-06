from __future__ import annotations

import asyncio
import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, HTTPException, Query
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


app = FastAPI(title="MIMIR Media Bridge", version="1.0.0")
_sessions: dict[str, SessionRecord] = {}
_event_bus = EventBus()


async def _emit_event(event_type: str, record: SessionRecord, attributes: dict[str, Any] | None = None) -> None:
    await _event_bus.publish(
        {
            "event_id": str(uuid.uuid4()),
            "event_type": event_type,
            "media_session_id": record.session_id,
            "call_id": record.call_id,
            "occurred_at": datetime.now(timezone.utc).isoformat(),
            "attributes": attributes or {},
        }
    )


@app.post("/v1/media/sessions", response_model=MediaSession, status_code=201)
async def create_media_session(request: CreateMediaSessionRequest) -> MediaSession:
    session_id = f"media-{uuid.uuid4()}"
    record = SessionRecord(session_id=session_id, call_id=request.call_id, status="created")
    _sessions[session_id] = record
    await _emit_event(
        "media.session.created",
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
async def start_media_session(session_id: str) -> MediaSession:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")
    if record.status in {"active", "terminated"}:
        return MediaSession(
            session_id=record.session_id,
            call_id=record.call_id,
            status=record.status,
            reason=record.reason,
        )

    record.status = "active"
    await _emit_event("media.session.active", record)
    await _emit_event("media.audio.first_packet", record, {"direction": "ai_to_rtp"})
    return MediaSession(session_id=record.session_id, call_id=record.call_id, status=record.status)


@app.post("/v1/media/sessions/{session_id}/stop", response_model=MediaSession)
async def stop_media_session(session_id: str, body: StopMediaSessionRequest) -> MediaSession:
    record = _sessions.get(session_id)
    if not record:
        raise HTTPException(status_code=404, detail="media session not found")
    record.status = "terminated"
    record.reason = body.reason
    await _emit_event("media.session.terminated", record, {"reason": body.reason})
    return MediaSession(
        session_id=record.session_id,
        call_id=record.call_id,
        status=record.status,
        reason=record.reason,
    )


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
    return {"status": "ok", "service": "media-bridge"}
