from __future__ import annotations

import asyncio
import json
import os
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .datastore import create_datastore, now_iso
from .media_bridge_client import MediaBridgeClient


class CallState(str, Enum):
    NEW = "NEW"
    INBOUND_RINGING = "INBOUND_RINGING"
    POLICY_CHECKED = "POLICY_CHECKED"
    MEDIA_ALLOCATING = "MEDIA_ALLOCATING"
    MEDIA_READY = "MEDIA_READY"
    ANSWERED = "ANSWERED"
    ACTIVE = "ACTIVE"
    TERMINATING = "TERMINATING"
    ENDED = "ENDED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class CallEvent:
    event_id: str
    call_id: str
    event_type: str
    occurred_at: str
    attributes: dict[str, Any]


class InvitePayload(BaseModel):
    call_id: str = Field(default_factory=lambda: f"call-{uuid.uuid4()}")
    caller: str
    callee: str
    called_extension: str
    sip_headers: dict[str, str] = Field(default_factory=dict)
    rtp: dict[str, Any]


class HangupPayload(BaseModel):
    reason: str = "normal_clearing"


class PolicyEngine:
    def __init__(self, allowed_extensions: set[str]) -> None:
        self.allowed_extensions = allowed_extensions

    def evaluate_invite(self, invite: InvitePayload) -> tuple[bool, str]:
        if invite.called_extension not in self.allowed_extensions:
            return False, "extension_not_allowed"
        if invite.sip_headers.get("X-Block-Call", "false").lower() == "true":
            return False, "blocked_by_header"
        return True, "ok"


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


app = FastAPI(title="MIMIR SIP Flow Handler", version="1.1.0")
media_client = MediaBridgeClient(os.getenv("MEDIA_BRIDGE_URL", "http://localhost:8081"))
policy_engine = PolicyEngine(set(os.getenv("ALLOWED_EXTENSIONS", "2001,2002,2003").split(",")))
datastore = create_datastore()
event_bus = EventBus()


def _reduce_state(events: list[dict[str, Any]]) -> CallState:
    state = CallState.NEW
    for event in events:
        mapping = {
            "call.new": CallState.NEW,
            "call.inbound_ringing": CallState.INBOUND_RINGING,
            "call.policy_checked": CallState.POLICY_CHECKED,
            "call.media_allocating": CallState.MEDIA_ALLOCATING,
            "call.media_ready": CallState.MEDIA_READY,
            "call.answered": CallState.ANSWERED,
            "call.active": CallState.ACTIVE,
            "call.terminating": CallState.TERMINATING,
            "call.ended": CallState.ENDED,
            "call.failed": CallState.FAILED,
        }
        state = mapping.get(event["event_type"], state)
    return state


async def _publish_event(call_id: str, event_type: str, attributes: dict[str, Any]) -> dict[str, Any]:
    event = CallEvent(
        event_id=f"evt-{uuid.uuid4()}",
        call_id=call_id,
        event_type=event_type,
        occurred_at=now_iso(),
        attributes=attributes,
    )
    payload = {
        "event_id": event.event_id,
        "call_id": event.call_id,
        "event_type": event.event_type,
        "occurred_at": event.occurred_at,
        "attributes": event.attributes,
    }
    await datastore.append_call_event(call_id, payload)
    current = await datastore.load_call_projection(call_id) or {"call_id": call_id}
    all_events = await datastore.load_call_events(call_id)
    current["state"] = _reduce_state(all_events).value
    current["updated_at"] = event.occurred_at
    current.update(attributes.get("projection", {}))
    await datastore.save_call_projection(call_id, current)
    await event_bus.publish(payload)
    return payload


@app.on_event("startup")
async def startup_registration() -> None:
    app.state.registration = {"status": "REGISTERED", "updated_at": now_iso()}
    asyncio.create_task(_consume_media_call_events())


async def _consume_media_call_events() -> None:
    async for event in media_client.stream_call_events():
        payload = json.loads(event["payload"])
        call_id = payload.get("call_id")
        if not call_id:
            continue
        await _publish_event(call_id, payload.get("event_type", "media.unknown"), {"projection": payload.get("attributes", {})})


@app.post("/v1/sip/invites")
async def inbound_invite(invite: InvitePayload, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> dict[str, Any]:
    reserved, prior = await datastore.reserve_idempotency("accept", idempotency_key, {"call_id": invite.call_id})
    if not reserved:
        projection = await datastore.load_call_projection(invite.call_id)
        return {"idempotent_replay": True, **(projection or prior or {"call_id": invite.call_id})}

    await _publish_event(invite.call_id, "call.new", {"projection": {"participant": invite.model_dump(exclude={"rtp", "sip_headers"}), "rtp": invite.rtp}})
    await _publish_event(invite.call_id, "call.inbound_ringing", {"projection": {"ringing": True}})

    accepted, policy_reason = policy_engine.evaluate_invite(invite)
    await _publish_event(invite.call_id, "call.policy_checked", {"projection": {"policy_reason": policy_reason}})
    if not accepted:
        await _publish_event(invite.call_id, "call.failed", {"projection": {"reason": policy_reason}})
        return {"call_id": invite.call_id, "state": CallState.FAILED.value, "action": "reject", "reason": policy_reason}

    await _publish_event(invite.call_id, "call.media_allocating", {})
    media = await media_client.create_session(
        {
            "call_id": invite.call_id,
            "participant": {"caller": invite.caller, "callee": invite.callee, "called_extension": invite.called_extension},
            "config": {
                "model": "gpt-4o-realtime-preview-2024-12-17",
                "voice": "alloy",
                "instructions": "Speak like the configured historical scientist.",
                "greeting": "Hello, this is your scientist speaking.",
                "input_codec": "g711_ulaw",
                "output_codec": "g711_ulaw",
                "sample_rate_hz": 8000,
            },
            "rtp": invite.rtp,
            "metadata": {"source": "sip-flow-handler"},
        }
    )

    await _publish_event(invite.call_id, "call.media_ready", {"projection": {"media_session_id": media["session_id"]}})
    started = await media_client.attach_media(media["session_id"], idempotency_key=f"attach-{idempotency_key}")
    if started["status"] == "active":
        await _publish_event(invite.call_id, "call.answered", {})
        await _publish_event(invite.call_id, "call.active", {})

    projection = await datastore.load_call_projection(invite.call_id)
    return {"call_id": invite.call_id, "action": "accept", **(projection or {})}


@app.post("/v1/sip/calls/{call_id}/hangup")
async def hangup(call_id: str, hangup: HangupPayload, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> dict[str, Any]:
    projection = await datastore.load_call_projection(call_id)
    if not projection:
        raise HTTPException(status_code=404, detail="call not found")

    reserved, prior = await datastore.reserve_idempotency("hangup", idempotency_key, {"call_id": call_id})
    if not reserved:
        return {"idempotent_replay": True, **(projection or prior or {"call_id": call_id})}

    await _publish_event(call_id, "call.terminating", {"projection": {"reason": hangup.reason}})
    session_id = projection.get("media_session_id")
    if session_id:
        await media_client.terminate_media(session_id, reason=hangup.reason, idempotency_key=f"term-{idempotency_key}")

    await _publish_event(call_id, "call.ended", {"projection": {"reason": hangup.reason}})
    projection = await datastore.load_call_projection(call_id)
    return projection or {"call_id": call_id, "state": CallState.ENDED.value}


@app.get("/v1/sip/calls/{call_id}")
async def get_call(call_id: str) -> dict[str, Any]:
    projection = await datastore.load_call_projection(call_id)
    if not projection:
        raise HTTPException(status_code=404, detail="call not found")
    return projection


@app.get("/v1/call-events")
async def stream_call_events():
    from fastapi.responses import StreamingResponse

    queue = event_bus.subscribe()

    async def event_generator():
        try:
            while True:
                event = await queue.get()
                yield f"event: {event['event_type']}\n"
                yield f"data: {json.dumps(event)}\n\n"
        finally:
            event_bus.unsubscribe(queue)

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.get("/healthz")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "sip-flow-handler"}
