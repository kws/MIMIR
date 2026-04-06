from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class BridgeSessionStatus(str, Enum):
    """Stable session states shared by controller + all backends."""

    CREATED = "created"
    ACTIVE = "active"
    TERMINATED = "terminated"


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
    model_name: str
    voice: str
    instructions: str
    greeting: str
    vad_mode: str


class MediaSettings(BaseModel):
    input_codec: str = "g711_ulaw"
    output_codec: str = "g711_ulaw"
    sample_rate_hz: int = 8000


class CreateMediaSessionRequest(BaseModel):
    call_id: str
    direction: Literal["inbound"] = "inbound"
    participant: SipParticipant
    ai_profile: MediaSessionConfig
    media_settings: MediaSettings = Field(default_factory=MediaSettings)
    rtp: RtpFlow
    metadata: dict[str, str] = Field(default_factory=dict)


class StopMediaSessionRequest(BaseModel):
    reason: str = "normal_clearing"


class MediaSession(BaseModel):
    session_id: str
    bridge_session_id: str
    call_id: str
    status: str
    reason: str | None = None
