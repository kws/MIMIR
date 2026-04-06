from __future__ import annotations

import asyncio
import json
import os
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .media_bridge_client import MediaBridgeClient


class CallState(str, Enum):
    REGISTERING = "registering"
    REGISTERED = "registered"
    INVITE_RECEIVED = "invite_received"
    POLICY_ACCEPTED = "policy_accepted"
    MEDIA_CREATING = "media_creating"
    MEDIA_ACTIVE = "media_active"
    TERMINATING = "terminating"
    TERMINATED = "terminated"
    FAILED = "failed"


class InvitePayload(BaseModel):
    call_id: str = Field(default_factory=lambda: f"call-{uuid.uuid4()}")
    caller: str
    callee: str
    called_extension: str
    sip_headers: dict[str, str] = Field(default_factory=dict)
    rtp: dict[str, Any]


class HangupPayload(BaseModel):
    reason: str = "normal_clearing"


@dataclass
class CallRecord:
    call_id: str
    state: CallState
    participant: dict[str, Any]
    rtp: dict[str, Any]
    media_session_id: str | None = None
    reason: str | None = None
    created_at: str = datetime.now(timezone.utc).isoformat()


class PolicyEngine:
    def __init__(self, allowed_extensions: set[str]) -> None:
        self.allowed_extensions = allowed_extensions

    def evaluate_invite(self, invite: InvitePayload) -> tuple[bool, str]:
        if invite.called_extension not in self.allowed_extensions:
            return False, "extension_not_allowed"
        if invite.sip_headers.get("X-Block-Call", "false").lower() == "true":
            return False, "blocked_by_header"
        return True, "ok"


app = FastAPI(title="MIMIR SIP Flow Handler", version="1.0.0")
media_client = MediaBridgeClient(os.getenv("MEDIA_BRIDGE_URL", "http://localhost:8081"))
policy_engine = PolicyEngine(set(os.getenv("ALLOWED_EXTENSIONS", "2001,2002,2003").split(",")))
_calls: dict[str, CallRecord] = {}


@app.on_event("startup")
async def startup_registration() -> None:
    # Placeholder for SIP REGISTER transaction lifecycle.
    app.state.registration = {"status": CallState.REGISTERED.value, "updated_at": datetime.now(timezone.utc).isoformat()}


@app.post("/v1/sip/invites")
async def inbound_invite(invite: InvitePayload) -> dict[str, Any]:
    accepted, policy_reason = policy_engine.evaluate_invite(invite)
    record = CallRecord(
        call_id=invite.call_id,
        state=CallState.INVITE_RECEIVED,
        participant={
            "caller": invite.caller,
            "callee": invite.callee,
            "called_extension": invite.called_extension,
        },
        rtp=invite.rtp,
    )
    _calls[record.call_id] = record

    if not accepted:
        record.state = CallState.FAILED
        record.reason = policy_reason
        return {
            "call_id": record.call_id,
            "state": record.state.value,
            "action": "reject",
            "reason": policy_reason,
        }

    record.state = CallState.POLICY_ACCEPTED
    record.state = CallState.MEDIA_CREATING

    media = await media_client.create_session(
        {
            "call_id": record.call_id,
            "participant": record.participant,
            "config": {
                "model": "gpt-4o-realtime-preview-2024-12-17",
                "voice": "alloy",
                "instructions": "Speak like the configured historical scientist.",
                "greeting": "Hello, this is your scientist speaking.",
                "input_codec": "g711_ulaw",
                "output_codec": "g711_ulaw",
                "sample_rate_hz": 8000,
            },
            "rtp": record.rtp,
            "metadata": {"source": "sip-flow-handler"},
        }
    )

    record.media_session_id = media["session_id"]
    started = await media_client.start_session(record.media_session_id)
    record.state = CallState.MEDIA_ACTIVE if started["status"] == "active" else CallState.FAILED

    asyncio.create_task(_consume_media_events(record.call_id, record.media_session_id))

    return {
        "call_id": record.call_id,
        "state": record.state.value,
        "action": "accept",
        "media_session_id": record.media_session_id,
    }


async def _consume_media_events(call_id: str, session_id: str) -> None:
    async for event in media_client.stream_events(session_id):
        payload = json.loads(event["payload"])
        if payload.get("event_type") == "media.session.terminated":
            record = _calls.get(call_id)
            if record:
                record.state = CallState.TERMINATED
                record.reason = payload.get("attributes", {}).get("reason")
            return


@app.post("/v1/sip/calls/{call_id}/hangup")
async def hangup(call_id: str, hangup: HangupPayload) -> dict[str, Any]:
    record = _calls.get(call_id)
    if not record:
        raise HTTPException(status_code=404, detail="call not found")

    record.state = CallState.TERMINATING
    if record.media_session_id:
        await media_client.stop_session(record.media_session_id, reason=hangup.reason)

    record.state = CallState.TERMINATED
    record.reason = hangup.reason

    return {
        "call_id": call_id,
        "state": record.state.value,
        "reason": record.reason,
    }


@app.get("/v1/sip/calls/{call_id}")
async def get_call(call_id: str) -> dict[str, Any]:
    record = _calls.get(call_id)
    if not record:
        raise HTTPException(status_code=404, detail="call not found")

    return {
        "call_id": record.call_id,
        "state": record.state.value,
        "participant": record.participant,
        "media_session_id": record.media_session_id,
        "reason": record.reason,
    }


@app.get("/healthz")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "sip-flow-handler"}
