from __future__ import annotations

import asyncio
import os
import socket
import uuid

import pytest
from mimir.mediabridge.backends import BackendRouter
from mimir.mediabridge.controller_contract import CallParticipant, CreateMediaSessionRequest, MediaSessionConfig, MediaSettings, RtpFlow
from mimir.mediabridge.live_rtp import LiveRtpHooks
from mimir.mediabridge.rtp import parse_rtp_packet
from mimir.mediabridge.runtimes import OPENAI_RUNTIME

pytestmark = pytest.mark.skipif(
    not os.getenv("OPENAI_API_KEY") or os.getenv("RUN_LIVE_OPENAI_RTP_TEST") != "1",
    reason="set OPENAI_API_KEY and RUN_LIVE_OPENAI_RTP_TEST=1 to run live OpenAI RTP verification",
)


@pytest.mark.asyncio
async def test_openai_runtime_can_emit_greeting_over_live_rtp() -> None:
    router = BackendRouter()
    backend = router.choose_backend(requested_runtime=OPENAI_RUNTIME, model_name="gpt-realtime-mini")

    remote_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    remote_sock.bind(("127.0.0.1", 0))
    remote_sock.setblocking(False)
    first_audio: list[float] = []

    session = backend.create(
        CreateMediaSessionRequest(
            call_id=f"live-{uuid.uuid4()}",
            direction="inbound",
            participant=CallParticipant(caller="1000", callee="2001", called_extension="2001"),
            ai_profile=MediaSessionConfig(
                model_name="gpt-realtime-mini",
                voice="verse",
                instructions="You are a concise phone assistant.",
                greeting="Hello from the live RTP test.",
                initialisation="Ring, ring. The phone is ringing. You pick it up and say: 'Hello from the live RTP test.'",
                vad_mode="server_vad",
            ),
            media_settings=MediaSettings(input_codec="g711_ulaw", output_codec="g711_ulaw", sample_rate_hz=8000),
            rtp=RtpFlow(
                local_address="127.0.0.1",
                local_port=0,
                remote_address="127.0.0.1",
                remote_port=remote_sock.getsockname()[1],
            ),
            metadata={"bridge_runtime": OPENAI_RUNTIME},
        ),
        session_id=f"media-{uuid.uuid4()}",
    )
    try:
        await backend.start(
            session,
            live=True,
            hooks=LiveRtpHooks(
                on_first_audio=lambda value: _append_value(first_audio, value),
                on_telemetry=lambda *_: _noop(),
                on_conversation_event=lambda *_: _noop(),
                on_failure=lambda reason, *_: _raise_failure(reason),
            ),
        )
        packet_bytes, _ = await asyncio.wait_for(asyncio.get_running_loop().sock_recvfrom(remote_sock, 2048), timeout=30.0)
        packet = parse_rtp_packet(packet_bytes)

        assert packet.payload
        assert first_audio
    finally:
        await backend.stop(session, reason="test_complete")
        remote_sock.close()


async def _append_value(target: list[float], value: float) -> None:
    target.append(value)


async def _noop() -> None:
    return


async def _raise_failure(reason: str) -> None:
    raise AssertionError(reason)
