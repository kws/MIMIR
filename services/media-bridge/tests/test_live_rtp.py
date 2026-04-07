from __future__ import annotations

import asyncio
import socket

import pytest

from mimir.mediabridge.audio import PcmAudio
from mimir.mediabridge.live_rtp import LiveRtpBridge, LiveRtpHooks
from mimir.mediabridge.rtp import (
    G711_ULAW_PAYLOAD_BYTES,
    RTP_PAYLOAD_TYPE_PCMU,
    RtpPacket,
    build_rtp_packet,
    parse_rtp_packet,
    reserve_rtp_socket,
)
from mimir.mediabridge.runtimes import RuntimeSessionTelemetry, RuntimeStreamEvent


class FakeRuntimeSession:
    def __init__(self) -> None:
        self.input_audio: asyncio.Queue[PcmAudio] = asyncio.Queue()
        self.output_events: asyncio.Queue[RuntimeStreamEvent | None] = asyncio.Queue()
        self.greeting_requests = 0
        self.closed = False

    async def send_audio(self, audio_input: PcmAudio) -> None:
        await self.input_audio.put(audio_input)

    async def request_greeting(self) -> None:
        self.greeting_requests += 1

    async def receive_event(self) -> RuntimeStreamEvent | None:
        return await self.output_events.get()

    async def close(self) -> None:
        self.closed = True
        await self.output_events.put(None)

    def telemetry(self) -> RuntimeSessionTelemetry:
        return RuntimeSessionTelemetry(vendor_session_id="fake-session")


@pytest.mark.asyncio
async def test_live_rtp_bridge_moves_audio_between_rtp_and_runtime() -> None:
    runtime_session = FakeRuntimeSession()
    first_audio: list[float] = []
    telemetry_events: list[tuple[float, float]] = []
    failures: list[str] = []

    remote_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    remote_sock.bind(("127.0.0.1", 0))
    remote_sock.setblocking(False)

    reservation = reserve_rtp_socket(
        bind_address="127.0.0.1",
        advertised_address="127.0.0.1",
        requested_port=0,
        port_range_start=23000,
        port_range_end=23010,
    )
    bridge = LiveRtpBridge(
        reservation=reservation,
        runtime_session=runtime_session,
        hooks=LiveRtpHooks(
            on_first_audio=lambda value: _append_first_audio(first_audio, value),
            on_telemetry=lambda tracker, _: _append_telemetry(telemetry_events, tracker.snapshot().packet_loss_pct, tracker.snapshot().jitter_ms),
            on_failure=lambda reason, *_: _append_failure(failures, reason),
        ),
    )

    try:
        await bridge.activate("127.0.0.1", remote_sock.getsockname()[1])

        inbound_packet = build_rtp_packet(
            RtpPacket(
                payload_type=RTP_PAYLOAD_TYPE_PCMU,
                sequence_number=1,
                timestamp=0,
                ssrc=99,
                payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
            )
        )
        remote_sock.sendto(inbound_packet, (reservation.advertised_address, reservation.local_port))

        inbound_audio = await asyncio.wait_for(runtime_session.input_audio.get(), timeout=1.0)
        assert inbound_audio.sample_rate_hz == 8000
        assert len(inbound_audio.pcm16) == 320

        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="audio",
                audio=PcmAudio(pcm16=b"\x00\x00" * 480, sample_rate_hz=24_000, channels=1),
            )
        )

        packet_bytes, _ = await asyncio.wait_for(asyncio.get_running_loop().sock_recvfrom(remote_sock, 2048), timeout=1.0)
        outbound_packet = parse_rtp_packet(packet_bytes)

        assert outbound_packet.payload_type == RTP_PAYLOAD_TYPE_PCMU
        assert len(outbound_packet.payload) == G711_ULAW_PAYLOAD_BYTES
        assert first_audio

        remote_sock.sendto(b"\x80", (reservation.advertised_address, reservation.local_port))
        await bridge.stop()

        assert telemetry_events
        assert not failures
    finally:
        remote_sock.close()


async def _append_first_audio(target: list[float], value: float) -> None:
    target.append(value)


async def _append_telemetry(target: list[tuple[float, float]], packet_loss_pct: float, jitter_ms: float) -> None:
    target.append((packet_loss_pct, jitter_ms))


async def _append_failure(target: list[str], reason: str) -> None:
    target.append(reason)
