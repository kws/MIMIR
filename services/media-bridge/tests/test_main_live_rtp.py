from __future__ import annotations

import asyncio
import socket

import pytest
from httpx import ASGITransport, AsyncClient

import app.main as media_main
from app.audio import PcmAudio
from app.rtp import G711_ULAW_PAYLOAD_BYTES, RTP_PAYLOAD_TYPE_PCMU, RtpPacket, build_rtp_packet, parse_rtp_packet
from app.runtimes import OPENAI_RUNTIME, RuntimeRunResult, RuntimeSessionTelemetry, RuntimeStreamEvent


class FakeRuntimeSession:
    def __init__(self) -> None:
        self.input_audio: asyncio.Queue[PcmAudio] = asyncio.Queue()
        self.output_events: asyncio.Queue[RuntimeStreamEvent | None] = asyncio.Queue()
        self.greeting_requests = 0

    async def send_audio(self, audio_input: PcmAudio) -> None:
        await self.input_audio.put(audio_input)

    async def request_greeting(self) -> None:
        self.greeting_requests += 1

    async def receive_event(self) -> RuntimeStreamEvent | None:
        return await self.output_events.get()

    async def close(self) -> None:
        await self.output_events.put(None)

    def telemetry(self) -> RuntimeSessionTelemetry:
        return RuntimeSessionTelemetry(vendor_session_id="fake-api-session")


class FakeLiveRuntime:
    runtime_name = OPENAI_RUNTIME
    input_sample_rate_hz = 24_000

    def __init__(self) -> None:
        self.last_session: FakeRuntimeSession | None = None

    async def start_session(self, request) -> FakeRuntimeSession:
        self.last_session = FakeRuntimeSession()
        return self.last_session

    async def run_fixture(self, request, input_audio, timeout_seconds) -> RuntimeRunResult:
        return RuntimeRunResult(
            runtime=self.runtime_name,
            model_name=request.model_name,
            output_audio=PcmAudio(pcm16=b"\x00\x00" * 480, sample_rate_hz=24_000, channels=1),
        )


@pytest.mark.asyncio
async def test_media_session_create_start_and_stop_exposes_resolved_rtp(monkeypatch: pytest.MonkeyPatch) -> None:
    backend = media_main.backend_router._backends[OPENAI_RUNTIME]
    fake_runtime = FakeLiveRuntime()
    original_runtime = backend.runtime
    backend.runtime = fake_runtime
    media_main._sessions.clear()
    media_main._idempotency.clear()

    remote_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    remote_sock.bind(("127.0.0.1", 0))
    remote_sock.setblocking(False)

    try:
        async with AsyncClient(transport=ASGITransport(app=media_main.app), base_url="http://testserver") as client:
            create_response = await client.post(
                "/v1/media/sessions",
                json={
                    "call_id": "call-123",
                    "direction": "inbound",
                    "participant": {"caller": "1000", "callee": "2001", "called_extension": "2001"},
                    "ai_profile": {
                        "model_name": "gpt-realtime-mini",
                        "voice": "verse",
                        "instructions": "Be helpful.",
                        "greeting": "Hello from the bridge.",
                        "initialisation": "Ring, ring. The phone is ringing. You pick it up and say: 'Hello from the bridge.'",
                        "vad_mode": "server_vad",
                    },
                    "media_settings": {"input_codec": "g711_ulaw", "output_codec": "g711_ulaw", "sample_rate_hz": 8000},
                    "rtp": {"local_address": "127.0.0.1", "local_port": 0, "remote_address": "", "remote_port": 0},
                    "metadata": {},
                },
            )
            assert create_response.status_code == 201
            created = create_response.json()
            assert created["rtp"]["local_port"] > 0
            assert created["rtp"]["remote_port"] == 0

            start_response = await client.post(
                f"/v1/media/sessions/{created['session_id']}/start",
                headers={"Idempotency-Key": "start-1"},
                json={"remote_rtp": {"address": "127.0.0.1", "port": remote_sock.getsockname()[1]}},
            )
            assert start_response.status_code == 200
            started = start_response.json()
            assert started["status"] == "active"
            assert started["rtp"]["remote_port"] == remote_sock.getsockname()[1]
            assert fake_runtime.last_session is not None
            assert fake_runtime.last_session.greeting_requests == 1

            local_port = started["rtp"]["local_port"]
            remote_sock.sendto(
                build_rtp_packet(
                    RtpPacket(
                        payload_type=RTP_PAYLOAD_TYPE_PCMU,
                        sequence_number=1,
                        timestamp=0,
                        ssrc=111,
                        payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
                    )
                ),
                ("127.0.0.1", local_port),
            )
            inbound_audio = await asyncio.wait_for(fake_runtime.last_session.input_audio.get(), timeout=1.0)
            assert inbound_audio.sample_rate_hz == 8000

            await fake_runtime.last_session.output_events.put(
                RuntimeStreamEvent(
                    event_type="audio",
                    audio=PcmAudio(pcm16=b"\x00\x00" * 480, sample_rate_hz=24_000, channels=1),
                )
            )
            packet_bytes, _ = await asyncio.wait_for(asyncio.get_running_loop().sock_recvfrom(remote_sock, 2048), timeout=1.0)
            packet = parse_rtp_packet(packet_bytes)
            assert packet.payload_type == RTP_PAYLOAD_TYPE_PCMU

            stop_response = await client.post(
                f"/v1/media/sessions/{created['session_id']}/stop",
                headers={"Idempotency-Key": "stop-1"},
                json={"reason": "normal_clearing"},
            )
            assert stop_response.status_code == 200
            assert stop_response.json()["status"] == "terminated"
    finally:
        backend.runtime = original_runtime
        remote_sock.close()
