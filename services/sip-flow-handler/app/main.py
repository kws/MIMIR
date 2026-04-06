from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel, Field

from .config_service import AIProfile, AIProfileConfigService
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
    direction: str = "inbound"
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
logger = logging.getLogger("sip-flow-handler")
profile_config = AIProfileConfigService(os.getenv("AI_PROFILE_CONFIG_PATH", "/tmp/mimir-ai-profiles.json"))
INVITE_STARTED_AT: dict[str, float] = {}

ALLOWED_TRUNK_SOURCES = {item.strip() for item in os.getenv("ALLOWED_TRUNK_SOURCES", "").split(",") if item.strip()}
MAX_ACTIVE_CALLS_PER_SOURCE = int(os.getenv("MAX_ACTIVE_CALLS_PER_SOURCE", "20"))
TERMINAL_STATES = {CallState.ENDED.value, CallState.FAILED.value}
OUTBOUND_BLOCKED_ERROR_CODE = "OUTBOUND_ORIGINATION_DISABLED"
OUTBOUND_BLOCKED_HTTP_STATUS = 403

INVITE_TO_ANSWER_LATENCY_SECONDS = Histogram(
    "sip_invite_to_answer_latency_seconds",
    "Latency from INVITE arrival in SIP handler to call answer.",
    buckets=(0.1, 0.25, 0.5, 1, 2, 3, 5, 10, 20, 30),
)
FIRST_AUDIO_LATENCY_SECONDS = Histogram(
    "sip_first_audio_latency_seconds",
    "Latency from INVITE arrival in SIP handler to first-audio observation.",
    buckets=(0.1, 0.25, 0.5, 1, 2, 3, 5, 10, 20, 30),
)
WS_RECONNECT_TOTAL = Counter(
    "sip_websocket_reconnect_total",
    "Count of websocket reconnect events reported by media bridge.",
    labelnames=("runtime",),
)
WS_ERROR_TOTAL = Counter(
    "sip_websocket_error_total",
    "Count of websocket errors reported by media bridge.",
    labelnames=("runtime",),
)
CALL_COMPLETION_TOTAL = Counter(
    "sip_call_completion_total",
    "Count of completed and failed calls by reason.",
    labelnames=("result", "reason"),
)


class AIProfileMappingRequest(BaseModel):
    called_extension: str
    callee: str
    profile: AIProfile


def _audit_log(event: str, details: dict[str, Any]) -> None:
    logger.warning("audit_event=%s details=%s", event, json.dumps(details, sort_keys=True))


def _structured_log(event: str, call_id: str | None = None, bridge_session_id: str | None = None, **fields: Any) -> None:
    payload: dict[str, Any] = {"event": event, "component": "sip-flow-handler", **fields}
    if call_id:
        payload["call_id"] = call_id
    if bridge_session_id:
        payload["bridge_session_id"] = bridge_session_id
    logger.info("structured=%s", json.dumps(payload, sort_keys=True))


async def _active_calls_for_source(source_id: str) -> int:
    projections = await datastore.list_call_projections()
    return sum(
        1
        for projection in projections
        if projection.get("source_id") == source_id and projection.get("state") not in TERMINAL_STATES
    )


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
        bridge_session_id = payload.get("bridge_session_id")
        attributes = payload.get("attributes", {})
        runtime = attributes.get("runtime", "python")
        reconnects = int(attributes.get("ws_reconnects", 0))
        ws_errors = int(attributes.get("ws_errors", 0))
        if reconnects:
            WS_RECONNECT_TOTAL.labels(runtime=runtime).inc(reconnects)
        if ws_errors:
            WS_ERROR_TOTAL.labels(runtime=runtime).inc(ws_errors)
        if payload.get("event_type") == "call.first_audio":
            started_at = INVITE_STARTED_AT.get(call_id)
            if started_at is not None:
                FIRST_AUDIO_LATENCY_SECONDS.observe(time.perf_counter() - started_at)
        _structured_log("media_event_received", call_id=call_id, bridge_session_id=bridge_session_id, event_type=payload.get("event_type"))
        await _publish_event(call_id, payload.get("event_type", "media.unknown"), {"projection": payload.get("attributes", {})})


@app.post("/v1/sip/invites")
async def inbound_invite(invite: InvitePayload, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> dict[str, Any]:
    INVITE_STARTED_AT[invite.call_id] = time.perf_counter()
    source_id = invite.sip_headers.get("X-Trunk-Source") or invite.sip_headers.get("X-Source")

    if invite.direction.lower() != "inbound" or invite.sip_headers.get("X-Originate-Request", "false").lower() == "true":
        _audit_log(
            "origination_attempt_rejected",
            {
                "error_code": OUTBOUND_BLOCKED_ERROR_CODE,
                "call_id": invite.call_id,
                "direction": invite.direction,
                "source_id": source_id,
                "caller": invite.caller,
                "callee": invite.callee,
            },
        )
        raise HTTPException(
            status_code=OUTBOUND_BLOCKED_HTTP_STATUS,
            detail={
                "error_code": OUTBOUND_BLOCKED_ERROR_CODE,
                "message": "Outbound call origination is disabled; only inbound INVITE requests are allowed.",
            },
        )

    if ALLOWED_TRUNK_SOURCES and source_id not in ALLOWED_TRUNK_SOURCES:
        _audit_log(
            "source_not_allowlisted",
            {"call_id": invite.call_id, "source_id": source_id, "allowed_sources": sorted(ALLOWED_TRUNK_SOURCES)},
        )
        raise HTTPException(status_code=403, detail={"error_code": "SOURCE_NOT_ALLOWLISTED", "message": "SIP source is not allowlisted"})

    if source_id:
        active_calls = await _active_calls_for_source(source_id)
        if active_calls >= MAX_ACTIVE_CALLS_PER_SOURCE:
            _audit_log(
                "source_rate_limited",
                {"call_id": invite.call_id, "source_id": source_id, "active_calls": active_calls, "limit": MAX_ACTIVE_CALLS_PER_SOURCE},
            )
            raise HTTPException(
                status_code=429,
                detail={"error_code": "SOURCE_RATE_LIMIT_EXCEEDED", "message": "Too many active calls for source/trunk"},
            )

    reserved, prior = await datastore.reserve_idempotency("accept", idempotency_key, {"call_id": invite.call_id})
    if not reserved:
        projection = await datastore.load_call_projection(invite.call_id)
        return {"idempotent_replay": True, **(projection or prior or {"call_id": invite.call_id})}

    await _publish_event(
        invite.call_id,
        "call.new",
        {
            "projection": {
                "participant": invite.model_dump(exclude={"rtp", "sip_headers"}),
                "rtp": invite.rtp,
                "source_id": source_id,
                "call_log_required": True,
            }
        },
    )
    _audit_log("call_logged", {"call_id": invite.call_id, "source_id": source_id, "direction": invite.direction})
    await _publish_event(invite.call_id, "call.inbound_ringing", {"projection": {"ringing": True}})

    accepted, policy_reason = policy_engine.evaluate_invite(invite)
    await _publish_event(invite.call_id, "call.policy_checked", {"projection": {"policy_reason": policy_reason}})
    if not accepted:
        await _publish_event(invite.call_id, "call.failed", {"projection": {"reason": policy_reason}})
        CALL_COMPLETION_TOTAL.labels(result="failed", reason=policy_reason).inc()
        INVITE_STARTED_AT.pop(invite.call_id, None)
        return {"call_id": invite.call_id, "state": CallState.FAILED.value, "action": "reject", "reason": policy_reason}

    await _publish_event(invite.call_id, "call.media_allocating", {})
    resolved_profile = profile_config.resolve(invite.called_extension, invite.callee)
    media = await media_client.create_session(
        {
            "call_id": invite.call_id,
            "direction": "inbound",
            "participant": {"caller": invite.caller, "callee": invite.callee, "called_extension": invite.called_extension},
            "ai_profile": resolved_profile.model_dump(),
            "media_settings": {"input_codec": "g711_ulaw", "output_codec": "g711_ulaw", "sample_rate_hz": 8000},
            "rtp": invite.rtp,
            "metadata": {"source": "sip-flow-handler"},
        }
    )

    await _publish_event(invite.call_id, "call.media_ready", {"projection": {"media_session_id": media["session_id"]}})
    started = await media_client.attach_media(media["session_id"], idempotency_key=f"attach-{idempotency_key}")
    if started["status"] == "active":
        started_at = INVITE_STARTED_AT.get(invite.call_id)
        if started_at is not None:
            INVITE_TO_ANSWER_LATENCY_SECONDS.observe(time.perf_counter() - started_at)
        await _publish_event(invite.call_id, "call.answered", {})
        await _publish_event(invite.call_id, "call.active", {})
        _structured_log("call_answered", call_id=invite.call_id, bridge_session_id=media["session_id"])

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
    CALL_COMPLETION_TOTAL.labels(result="ended", reason=hangup.reason).inc()
    INVITE_STARTED_AT.pop(call_id, None)
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


@app.get("/metrics")
async def metrics() -> PlainTextResponse:
    return PlainTextResponse(generate_latest().decode("utf-8"), media_type=CONTENT_TYPE_LATEST)


@app.put("/v1/config/ai-profiles")
async def upsert_ai_profile_mapping(request: AIProfileMappingRequest) -> dict[str, Any]:
    profile_config.upsert(request.called_extension, request.callee, request.profile)
    return {
        "status": "saved",
        "called_extension": request.called_extension,
        "callee": request.callee,
        "profile": request.profile.model_dump(),
    }
