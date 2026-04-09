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
    decode_ulaw_payload,
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
        self.interrupts = 0
        self.instruction_updates: list[str] = []
        self.response_requests: list[str | None] = []

    async def send_audio(self, audio_input: PcmAudio) -> None:
        await self.input_audio.put(audio_input)

    async def request_greeting(self) -> None:
        self.greeting_requests += 1

    async def interrupt(self) -> None:
        self.interrupts += 1

    async def append_instructions(self, text: str) -> None:
        self.instruction_updates.append(text)

    async def request_response(self, prompt: str | None = None) -> None:
        self.response_requests.append(prompt)

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
            on_telemetry=lambda tracker, _: _append_telemetry(
                telemetry_events, tracker.snapshot().packet_loss_pct, tracker.snapshot().jitter_ms
            ),
            on_conversation_event=lambda *_: _noop(),
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


@pytest.mark.asyncio
async def test_live_rtp_bridge_reorders_small_out_of_order_bursts() -> None:
    runtime_session = FakeRuntimeSession()
    telemetry_events = []

    remote_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    remote_sock.bind(("127.0.0.1", 0))
    remote_sock.setblocking(False)

    reservation = reserve_rtp_socket(
        bind_address="127.0.0.1",
        advertised_address="127.0.0.1",
        requested_port=0,
        port_range_start=23100,
        port_range_end=23110,
    )
    bridge = LiveRtpBridge(
        reservation=reservation,
        runtime_session=runtime_session,
        hooks=LiveRtpHooks(
            on_first_audio=lambda _: _noop(),
            on_telemetry=lambda tracker, _: _append_detailed_telemetry(telemetry_events, tracker.snapshot()),
            on_conversation_event=lambda *_: _noop(),
            on_failure=lambda *_: _noop(),
        ),
    )

    first_payload = b"\xff" * G711_ULAW_PAYLOAD_BYTES
    second_payload = b"\x7f" * G711_ULAW_PAYLOAD_BYTES
    third_payload = b"\x00" * G711_ULAW_PAYLOAD_BYTES

    try:
        await bridge.activate("127.0.0.1", remote_sock.getsockname()[1])
        remote_sock.sendto(
            build_rtp_packet(
                RtpPacket(
                    payload_type=RTP_PAYLOAD_TYPE_PCMU,
                    sequence_number=1,
                    timestamp=0,
                    ssrc=99,
                    payload=first_payload,
                )
            ),
            (reservation.advertised_address, reservation.local_port),
        )

        inbound_audio_first = await asyncio.wait_for(runtime_session.input_audio.get(), timeout=1.0)
        assert inbound_audio_first.pcm16 == decode_ulaw_payload(first_payload).pcm16

        remote_sock.sendto(
            build_rtp_packet(
                RtpPacket(
                    payload_type=RTP_PAYLOAD_TYPE_PCMU,
                    sequence_number=3,
                    timestamp=320,
                    ssrc=99,
                    payload=third_payload,
                )
            ),
            (reservation.advertised_address, reservation.local_port),
        )

        await asyncio.sleep(0.05)
        assert runtime_session.input_audio.empty()

        remote_sock.sendto(
            build_rtp_packet(
                RtpPacket(
                    payload_type=RTP_PAYLOAD_TYPE_PCMU,
                    sequence_number=2,
                    timestamp=160,
                    ssrc=99,
                    payload=second_payload,
                )
            ),
            (reservation.advertised_address, reservation.local_port),
        )

        inbound_audio_second = await asyncio.wait_for(runtime_session.input_audio.get(), timeout=1.0)
        inbound_audio_third = await asyncio.wait_for(runtime_session.input_audio.get(), timeout=1.0)

        assert inbound_audio_second.pcm16 == decode_ulaw_payload(second_payload).pcm16
        assert inbound_audio_third.pcm16 == decode_ulaw_payload(third_payload).pcm16

        await bridge.stop()

        assert len(telemetry_events) == 1
        snapshot = telemetry_events[0]
        assert snapshot.packet_loss_pct == 0.0
        assert snapshot.missing_packets == 0
        assert snapshot.duplicate_packets == 0
        assert snapshot.out_of_order_packets == 1
    finally:
        remote_sock.close()


@pytest.mark.asyncio
async def test_live_rtp_bridge_marks_missing_packets_when_jitter_window_is_exceeded() -> None:
    runtime_session = FakeRuntimeSession()
    telemetry_events = []

    remote_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    remote_sock.bind(("127.0.0.1", 0))
    remote_sock.setblocking(False)

    reservation = reserve_rtp_socket(
        bind_address="127.0.0.1",
        advertised_address="127.0.0.1",
        requested_port=0,
        port_range_start=23200,
        port_range_end=23210,
    )
    bridge = LiveRtpBridge(
        reservation=reservation,
        runtime_session=runtime_session,
        hooks=LiveRtpHooks(
            on_first_audio=lambda _: _noop(),
            on_telemetry=lambda tracker, _: _append_detailed_telemetry(telemetry_events, tracker.snapshot()),
            on_conversation_event=lambda *_: _noop(),
            on_failure=lambda *_: _noop(),
        ),
    )

    try:
        await bridge.activate("127.0.0.1", remote_sock.getsockname()[1])

        for sequence_number, timestamp in ((1, 0), (3, 320), (4, 480), (5, 640), (6, 800)):
            remote_sock.sendto(
                build_rtp_packet(
                    RtpPacket(
                        payload_type=RTP_PAYLOAD_TYPE_PCMU,
                        sequence_number=sequence_number,
                        timestamp=timestamp,
                        ssrc=99,
                        payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
                    )
                ),
                (reservation.advertised_address, reservation.local_port),
            )

        for _ in range(5):
            await asyncio.wait_for(runtime_session.input_audio.get(), timeout=1.0)

        await bridge.stop()

        assert len(telemetry_events) == 1
        snapshot = telemetry_events[0]
        assert snapshot.packet_loss_pct == pytest.approx(16.6667, abs=0.001)
        assert snapshot.missing_packets == 1
        assert snapshot.duplicate_packets == 0
        assert snapshot.out_of_order_packets == 4
        assert snapshot.max_buffered_packets == 4
    finally:
        remote_sock.close()


@pytest.mark.asyncio
async def test_live_rtp_bridge_normalizes_conversation_events_and_commands() -> None:
    runtime_session = FakeRuntimeSession()
    conversation_events: list[tuple[str, dict, bool]] = []

    remote_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    remote_sock.bind(("127.0.0.1", 0))
    remote_sock.setblocking(False)

    reservation = reserve_rtp_socket(
        bind_address="127.0.0.1",
        advertised_address="127.0.0.1",
        requested_port=0,
        port_range_start=23300,
        port_range_end=23310,
    )
    bridge = LiveRtpBridge(
        reservation=reservation,
        runtime_session=runtime_session,
        hooks=LiveRtpHooks(
            on_first_audio=lambda _: _noop(),
            on_telemetry=lambda *_: _noop(),
            on_conversation_event=lambda event_type, attributes, transient: _append_conversation_event(
                conversation_events, event_type, attributes, transient
            ),
            on_failure=lambda *_: _noop(),
        ),
    )

    try:
        await bridge.activate("127.0.0.1", remote_sock.getsockname()[1])
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="conversation.user.transcript.delta",
                attributes={"speaker": "user", "delta": "Hello "},
                transient=True,
            )
        )
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="conversation.user.turn.completed",
                attributes={"speaker": "user", "text": "Hello there"},
            )
        )
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="conversation.assistant.turn.started",
                attributes={"speaker": "assistant", "vendor_turn_id": "resp-1"},
                transient=True,
            )
        )
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="conversation.assistant.transcript.delta",
                attributes={"speaker": "assistant", "vendor_turn_id": "resp-1", "delta": "Hi "},
                transient=True,
            )
        )
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="conversation.assistant.turn.completed",
                attributes={"speaker": "assistant", "vendor_turn_id": "resp-1", "text": "Hi there"},
            )
        )
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="conversation.assistant.turn.started",
                attributes={"speaker": "assistant", "vendor_turn_id": "resp-2"},
                transient=True,
            )
        )
        await runtime_session.output_events.put(
            RuntimeStreamEvent(
                event_type="clear",
                attributes={"reason": "user_barge_in"},
            )
        )

        await asyncio.sleep(0.1)

        assert [event_type for event_type, _, _ in conversation_events] == [
            "conversation.user.transcript.delta",
            "conversation.user.turn.completed",
            "conversation.assistant.turn.started",
            "conversation.assistant.transcript.delta",
            "conversation.assistant.turn.completed",
            "conversation.assistant.turn.started",
            "conversation.interruption",
        ]
        assert conversation_events[0][2] is True
        assert conversation_events[1][1]["turn_index"] == 1
        assert conversation_events[4][1]["turn_index"] == 2
        assert conversation_events[6][1]["reason"] == "user_barge_in"

        append_result = await bridge.append_instructions("Be brief.")
        request_result = await bridge.request_response("Answer crisply.")
        bridge.outbound_stream.enqueue_audio(PcmAudio(pcm16=b"\x00\x00" * 160, sample_rate_hz=8_000, channels=1))
        interrupt_result = await bridge.interrupt()

        assert append_result["instruction_override_text"] == "Be brief."
        assert request_result == {"prompt": "Answer crisply."}
        assert interrupt_result == {}
        assert runtime_session.instruction_updates == ["Be brief."]
        assert runtime_session.response_requests == ["Answer crisply."]
        assert runtime_session.interrupts == 1
        assert bridge.outbound_stream.next_packet() is None
    finally:
        await bridge.stop()
        remote_sock.close()


async def _append_first_audio(target: list[float], value: float) -> None:
    target.append(value)


async def _append_telemetry(target: list[tuple[float, float]], packet_loss_pct: float, jitter_ms: float) -> None:
    target.append((packet_loss_pct, jitter_ms))


async def _append_failure(target: list[str], reason: str) -> None:
    target.append(reason)


async def _append_detailed_telemetry(target: list, snapshot) -> None:
    target.append(snapshot)


async def _append_conversation_event(target: list, event_type: str, attributes: dict, transient: bool) -> None:
    target.append((event_type, attributes, transient))


async def _noop(*_args) -> None:
    return
