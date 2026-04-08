from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Any, Literal

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import PlainTextResponse, StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel, Field

from .config_service import AIProfile, AIProfileConfigService
from .datastore import create_datastore, now_iso
from .media_bridge_client import MediaBridgeClient


class CallState(str, Enum):
    NEW = "NEW"
    INBOUND_RECEIVED = "INBOUND_RECEIVED"
    POLICY_CHECKED = "POLICY_CHECKED"
    MEDIA_REQUESTED = "MEDIA_REQUESTED"
    MEDIA_READY = "MEDIA_READY"
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


class AdapterReference(BaseModel):
    name: str
    account_id: str | None = None
    call_id: str | None = None
    metadata: dict[str, str] = Field(default_factory=dict)


class CallParticipant(BaseModel):
    caller: str
    callee: str
    called_extension: str


class RtpFlow(BaseModel):
    local_address: str
    local_port: int
    remote_address: str
    remote_port: int


class RemoteRtpEndpoint(BaseModel):
    address: str
    port: int = Field(ge=1, le=65535)


class InboundCallRequest(BaseModel):
    call_id: str = Field(default_factory=lambda: f"call-{uuid.uuid4()}")
    direction: str = "inbound"
    adapter: AdapterReference
    participant: CallParticipant
    metadata: dict[str, str] = Field(default_factory=dict)
    rtp: RtpFlow
    media_start_mode: Literal["immediate", "deferred"] = "immediate"


class HangupPayload(BaseModel):
    reason: str = "normal_clearing"


class AttachMediaRequest(BaseModel):
    remote_rtp: RemoteRtpEndpoint | None = None


class AIProfileMappingRequest(BaseModel):
    called_extension: str
    callee: str
    profile: AIProfile


class PolicyEngine:
    def __init__(self, allowed_extensions: set[str]) -> None:
        self.allowed_extensions = allowed_extensions

    def evaluate_call(self, request: InboundCallRequest) -> tuple[bool, str]:
        if request.participant.called_extension not in self.allowed_extensions:
            return False, "extension_not_allowed"
        if request.metadata.get("block_call", "false").lower() == "true":
            return False, "blocked_by_metadata"
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


app = FastAPI(title="MIMIR Orchestrator", version="2.0.0")
media_client = MediaBridgeClient(os.getenv("MEDIA_BRIDGE_URL", "http://localhost:8081"))
policy_engine = PolicyEngine(set(os.getenv("ALLOWED_EXTENSIONS", "2001,2002,2003").split(",")))
datastore = create_datastore()
event_bus = EventBus()
logger = logging.getLogger("orchestrator")
profile_config = AIProfileConfigService(os.getenv("AI_PROFILE_CONFIG_PATH", "/tmp/mimir-ai-profiles.json"))
CALL_STARTED_AT: dict[str, float] = {}

ALLOWED_ADAPTERS = {item.strip() for item in os.getenv("ALLOWED_ADAPTERS", "").split(",") if item.strip()}
MAX_ACTIVE_CALLS_PER_ADAPTER = int(os.getenv("MAX_ACTIVE_CALLS_PER_ADAPTER", "20"))
TERMINAL_STATES = {CallState.ENDED.value, CallState.FAILED.value}
OUTBOUND_BLOCKED_ERROR_CODE = "OUTBOUND_CALLS_NOT_SUPPORTED"
OUTBOUND_BLOCKED_HTTP_STATUS = 403

INBOUND_TO_ACTIVE_LATENCY_SECONDS = Histogram(
    "orchestrator_inbound_to_active_latency_seconds",
    "Latency from inbound call receipt in the orchestrator to active media.",
    buckets=(0.1, 0.25, 0.5, 1, 2, 3, 5, 10, 20, 30),
)
FIRST_AUDIO_LATENCY_SECONDS = Histogram(
    "orchestrator_first_audio_latency_seconds",
    "Latency from inbound call receipt in the orchestrator to first audio.",
    buckets=(0.1, 0.25, 0.5, 1, 2, 3, 5, 10, 20, 30),
)
MEDIA_WS_RECONNECT_TOTAL = Counter(
    "orchestrator_media_websocket_reconnect_total",
    "Count of websocket reconnect events reported by the media bridge.",
    labelnames=("runtime",),
)
MEDIA_WS_ERROR_TOTAL = Counter(
    "orchestrator_media_websocket_error_total",
    "Count of websocket errors reported by the media bridge.",
    labelnames=("runtime",),
)
CALL_COMPLETION_TOTAL = Counter(
    "orchestrator_call_completion_total",
    "Count of completed and failed calls by reason.",
    labelnames=("result", "reason"),
)


def _audit_log(event: str, details: dict[str, Any]) -> None:
    logger.warning("audit_event=%s details=%s", event, json.dumps(details, sort_keys=True))


def _structured_log(event: str, call_id: str | None = None, bridge_session_id: str | None = None, **fields: Any) -> None:
    payload: dict[str, Any] = {"event": event, "component": "orchestrator", **fields}
    if call_id:
        payload["call_id"] = call_id
    if bridge_session_id:
        payload["bridge_session_id"] = bridge_session_id
    logger.info("structured=%s", json.dumps(payload, sort_keys=True))


async def _active_calls_for_adapter(adapter_name: str) -> int:
    projections = await datastore.list_call_projections()
    return sum(
        1
        for projection in projections
        if projection.get("adapter", {}).get("name") == adapter_name and projection.get("state") not in TERMINAL_STATES
    )


def _reduce_state(events: list[dict[str, Any]]) -> CallState:
    state = CallState.NEW
    for event in events:
        mapping = {
            "call.new": CallState.NEW,
            "call.inbound_received": CallState.INBOUND_RECEIVED,
            "call.policy_checked": CallState.POLICY_CHECKED,
            "call.media_requested": CallState.MEDIA_REQUESTED,
            "media.session.created": CallState.MEDIA_READY,
            "media.session.active": CallState.ACTIVE,
            "call.terminating": CallState.TERMINATING,
            "call.ended": CallState.ENDED,
            "media.session.ended": CallState.ENDED,
            "call.failed": CallState.FAILED,
            "media.session.failed": CallState.FAILED,
        }
        state = mapping.get(event["event_type"], state)
    return state


async def _update_projection(call_id: str, updates: dict[str, Any]) -> dict[str, Any]:
    current = await datastore.load_call_projection(call_id) or {"call_id": call_id}
    current.update(updates)
    current["updated_at"] = now_iso()
    await datastore.save_call_projection(call_id, current)
    return current


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


def _attach_media_body(request: AttachMediaRequest) -> dict[str, Any] | None:
    if request.remote_rtp is None:
        return None
    return {"remote_rtp": request.remote_rtp.model_dump()}


async def _attach_media_for_call(
    call_id: str,
    session_id: str,
    *,
    idempotency_key: str,
    request: AttachMediaRequest,
) -> dict[str, Any]:
    started = await media_client.attach_media(session_id, idempotency_key=f"attach-{idempotency_key}", body=_attach_media_body(request))
    await _update_projection(call_id, {"bridge_rtp": started.get("rtp"), "media_status": started.get("status")})
    _structured_log(
        "media_session_attached",
        call_id=call_id,
        bridge_session_id=session_id,
        media_status=started["status"],
    )
    return started


@app.on_event("startup")
async def startup() -> None:
    asyncio.create_task(_consume_media_events())


async def _consume_media_events() -> None:
    async for event in media_client.stream_media_events():
        payload = json.loads(event["payload"])
        call_id = payload.get("call_id")
        if not call_id:
            continue

        event_type = payload.get("event_type", "media.unknown")
        bridge_session_id = payload.get("bridge_session_id")
        attributes = payload.get("attributes", {})
        runtime = attributes.get("runtime", "python")
        reconnects = int(attributes.get("ws_reconnects", 0) or 0)
        ws_errors = int(attributes.get("ws_errors", 0) or 0)

        if reconnects:
            MEDIA_WS_RECONNECT_TOTAL.labels(runtime=runtime).inc(reconnects)
        if ws_errors:
            MEDIA_WS_ERROR_TOTAL.labels(runtime=runtime).inc(ws_errors)

        if event_type == "media.session.active":
            started_at = CALL_STARTED_AT.get(call_id)
            if started_at is not None:
                INBOUND_TO_ACTIVE_LATENCY_SECONDS.observe(time.perf_counter() - started_at)
        elif event_type == "media.first_audio":
            started_at = CALL_STARTED_AT.get(call_id)
            if started_at is not None:
                FIRST_AUDIO_LATENCY_SECONDS.observe(time.perf_counter() - started_at)
        elif event_type == "media.session.ended":
            CALL_COMPLETION_TOTAL.labels(result="ended", reason=attributes.get("reason", "normal_clearing")).inc()
            CALL_STARTED_AT.pop(call_id, None)

        projection = dict(attributes)
        if payload.get("media_session_id"):
            projection.setdefault("media_session_id", payload["media_session_id"])
        if bridge_session_id:
            projection.setdefault("bridge_session_id", bridge_session_id)

        _structured_log("media_event_received", call_id=call_id, bridge_session_id=bridge_session_id, event_type=event_type)
        await _publish_event(call_id, event_type, {"projection": projection})


@app.post("/v1/calls/inbound")
async def create_inbound_call(request: InboundCallRequest, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> dict[str, Any]:
    CALL_STARTED_AT[request.call_id] = time.perf_counter()
    adapter_name = request.adapter.name

    if request.direction.lower() != "inbound":
        _audit_log(
            "outbound_call_rejected",
            {
                "error_code": OUTBOUND_BLOCKED_ERROR_CODE,
                "call_id": request.call_id,
                "direction": request.direction,
                "adapter_name": adapter_name,
                "caller": request.participant.caller,
                "callee": request.participant.callee,
            },
        )
        raise HTTPException(
            status_code=OUTBOUND_BLOCKED_HTTP_STATUS,
            detail={
                "error_code": OUTBOUND_BLOCKED_ERROR_CODE,
                "message": "The orchestrator accepts inbound calls from edge adapters only.",
            },
        )

    if ALLOWED_ADAPTERS and adapter_name not in ALLOWED_ADAPTERS:
        _audit_log(
            "adapter_not_allowlisted",
            {"call_id": request.call_id, "adapter_name": adapter_name, "allowed_adapters": sorted(ALLOWED_ADAPTERS)},
        )
        raise HTTPException(status_code=403, detail={"error_code": "ADAPTER_NOT_ALLOWLISTED", "message": "Call adapter is not allowlisted"})

    active_calls = await _active_calls_for_adapter(adapter_name)
    if active_calls >= MAX_ACTIVE_CALLS_PER_ADAPTER:
        _audit_log(
            "adapter_rate_limited",
            {
                "call_id": request.call_id,
                "adapter_name": adapter_name,
                "active_calls": active_calls,
                "limit": MAX_ACTIVE_CALLS_PER_ADAPTER,
            },
        )
        raise HTTPException(
            status_code=429,
            detail={"error_code": "ADAPTER_RATE_LIMIT_EXCEEDED", "message": "Too many active calls for adapter"},
        )

    reserved, prior = await datastore.reserve_idempotency("accept", idempotency_key, {"call_id": request.call_id})
    if not reserved:
        projection = await datastore.load_call_projection(request.call_id)
        return {"idempotent_replay": True, **(projection or prior or {"call_id": request.call_id})}

    await _publish_event(
        request.call_id,
        "call.new",
        {
            "projection": {
                "participant": request.participant.model_dump(),
                "adapter": request.adapter.model_dump(),
                "metadata": request.metadata,
                "rtp": request.rtp.model_dump(),
                "media_start_mode": request.media_start_mode,
                "call_log_required": True,
            }
        },
    )
    _audit_log(
        "call_logged",
        {"call_id": request.call_id, "adapter_name": adapter_name, "direction": request.direction},
    )
    await _publish_event(request.call_id, "call.inbound_received", {"projection": {"received": True}})

    accepted, policy_reason = policy_engine.evaluate_call(request)
    await _publish_event(request.call_id, "call.policy_checked", {"projection": {"policy_reason": policy_reason}})
    if not accepted:
        await _publish_event(request.call_id, "call.failed", {"projection": {"reason": policy_reason}})
        CALL_COMPLETION_TOTAL.labels(result="failed", reason=policy_reason).inc()
        CALL_STARTED_AT.pop(request.call_id, None)
        return {"call_id": request.call_id, "state": CallState.FAILED.value, "action": "reject", "reason": policy_reason}

    await _publish_event(request.call_id, "call.media_requested", {})
    resolved_profile = profile_config.resolve(request.participant.called_extension, request.participant.callee)
    media = await media_client.create_session(
        {
            "call_id": request.call_id,
            "direction": "inbound",
            "participant": request.participant.model_dump(),
            "ai_profile": resolved_profile.model_dump(exclude_none=True),
            "media_settings": {"input_codec": "g711_ulaw", "output_codec": "g711_ulaw", "sample_rate_hz": 8000},
            "rtp": request.rtp.model_dump(),
            "metadata": {"adapter_name": adapter_name, **request.metadata},
        }
    )
    await _update_projection(
        request.call_id,
        {
            "media_session_id": media["session_id"],
            "bridge_session_id": media["bridge_session_id"],
            "bridge_rtp": media.get("rtp"),
        },
    )

    if request.media_start_mode == "deferred":
        _structured_log(
            "media_session_attach_deferred",
            call_id=request.call_id,
            bridge_session_id=media["session_id"],
            media_status=media["status"],
        )
        projection = await datastore.load_call_projection(request.call_id)
        return {"call_id": request.call_id, "action": "accept", **(projection or {})}

    await _attach_media_for_call(request.call_id, media["session_id"], idempotency_key=idempotency_key, request=AttachMediaRequest())
    projection = await datastore.load_call_projection(request.call_id)
    return {"call_id": request.call_id, "action": "accept", **(projection or {})}


@app.post("/v1/calls/{call_id}/media/attach")
async def attach_call_media(
    call_id: str,
    request: AttachMediaRequest,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
) -> dict[str, Any]:
    projection = await datastore.load_call_projection(call_id)
    if not projection:
        raise HTTPException(status_code=404, detail="call not found")

    session_id = projection.get("media_session_id")
    if not session_id:
        raise HTTPException(status_code=409, detail="media session is not ready")

    reserved, prior = await datastore.reserve_idempotency("attach", idempotency_key, {"call_id": call_id, "media_session_id": session_id})
    if not reserved:
        projection = await datastore.load_call_projection(call_id)
        return {"idempotent_replay": True, **(projection or prior or {"call_id": call_id})}

    await _publish_event(
        call_id,
        "call.media_attach_requested",
        {"projection": {"adapter_remote_rtp": request.remote_rtp.model_dump() if request.remote_rtp else None}},
    )
    started = await _attach_media_for_call(call_id, session_id, idempotency_key=idempotency_key, request=request)
    projection = await datastore.load_call_projection(call_id)
    return {"call_id": call_id, "action": "attach", "media_status": started["status"], **(projection or {})}


@app.post("/v1/calls/{call_id}/hangup")
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
    else:
        await _publish_event(call_id, "call.ended", {"projection": {"reason": hangup.reason}})
        CALL_COMPLETION_TOTAL.labels(result="ended", reason=hangup.reason).inc()
        CALL_STARTED_AT.pop(call_id, None)

    projection = await datastore.load_call_projection(call_id)
    return projection or {"call_id": call_id, "state": CallState.ENDED.value}


@app.get("/v1/calls/{call_id}")
async def get_call(call_id: str) -> dict[str, Any]:
    projection = await datastore.load_call_projection(call_id)
    if not projection:
        raise HTTPException(status_code=404, detail="call not found")
    return projection


@app.get("/v1/call-events")
async def stream_call_events() -> StreamingResponse:
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
    return {"status": "ok", "service": "orchestrator"}


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
