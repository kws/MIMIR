from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any

from .audio import PcmAudio
from .rtp import (
    G711_ULAW_FRAME_MS,
    G711_ULAW_PAYLOAD_BYTES,
    RTP_PAYLOAD_TYPE_PCMU,
    RtpInboundTelemetryTracker,
    RtpOutboundStream,
    RtpPacketError,
    RtpSocketReservation,
    _sequence_delta,
    decode_ulaw_payload,
    parse_rtp_packet,
)
from .runtimes import RuntimeSession, RuntimeSessionTelemetry, RuntimeStreamEvent

INBOUND_JITTER_BUFFER_PACKETS = 3


@dataclass(slots=True)
class LiveRtpHooks:
    on_first_audio: Callable[[float], Awaitable[None]]
    on_telemetry: Callable[[RtpInboundTelemetryTracker, RuntimeSessionTelemetry], Awaitable[None]]
    on_conversation_event: Callable[[str, dict[str, Any], bool], Awaitable[None]]
    on_failure: Callable[[str, RtpInboundTelemetryTracker, RuntimeSessionTelemetry], Awaitable[None]]


@dataclass(slots=True)
class _ConversationTurnState:
    speaker: str
    turn_id: str | None = None
    turn_index: int = 0
    vendor_turn_id: str | None = None
    text_parts: list[str] = field(default_factory=list)

    def reset(self) -> None:
        self.turn_id = None
        self.turn_index = 0
        self.vendor_turn_id = None
        self.text_parts.clear()


class _SessionProtocol(asyncio.DatagramProtocol):
    def __init__(self, bridge: "LiveRtpBridge") -> None:
        self.bridge = bridge

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        self.bridge.handle_datagram(data, addr)

    def connection_lost(self, exc: Exception | None) -> None:
        if exc is not None:
            self.bridge.report_transport_failure(exc)


class LiveRtpBridge:
    def __init__(
        self,
        reservation: RtpSocketReservation,
        runtime_session: RuntimeSession,
        hooks: LiveRtpHooks,
    ) -> None:
        self.reservation = reservation
        self.runtime_session = runtime_session
        self.hooks = hooks
        self.inbound_telemetry = RtpInboundTelemetryTracker()
        self.outbound_stream = RtpOutboundStream(payload_type=RTP_PAYLOAD_TYPE_PCMU, payload_bytes=G711_ULAW_PAYLOAD_BYTES)
        self.transport: asyncio.DatagramTransport | None = None
        self.protocol: _SessionProtocol | None = None
        self.remote_target: tuple[str, int] | None = None
        self._inbound_audio_queue: asyncio.Queue[PcmAudio] = asyncio.Queue()
        self._inbound_packet_buffer: dict[int, bytes] = {}
        self._expected_sequence_number: int | None = None
        self._tasks: list[asyncio.Task[Any]] = []
        self._closed = False
        self._failure_emitted = False
        self._telemetry_emitted = False
        self._first_audio_emitted = False
        self._started_at: float | None = None
        self._conversation_status = "idle"
        self._conversation_turn_index = 0
        self._user_turn = _ConversationTurnState(speaker="user")
        self._assistant_turn = _ConversationTurnState(speaker="assistant")
        self._instruction_override_parts: list[str] = []

    async def activate(self, remote_address: str, remote_port: int) -> None:
        if self._closed:
            raise RuntimeError("live RTP bridge is closed")
        if self.transport is not None:
            raise RuntimeError("live RTP bridge is already active")
        if not remote_address or remote_port <= 0:
            raise RuntimeError("remote RTP target is required before activation")

        self.remote_target = (remote_address, remote_port)
        self._started_at = asyncio.get_running_loop().time()
        transport, protocol = await asyncio.get_running_loop().create_datagram_endpoint(
            lambda: _SessionProtocol(self),
            sock=self.reservation.sock,
        )
        self.transport = transport
        self.protocol = protocol
        self._tasks = [
            asyncio.create_task(self._forward_inbound_audio()),
            asyncio.create_task(self._forward_runtime_audio()),
            asyncio.create_task(self._sender_loop()),
        ]

    def handle_datagram(self, data: bytes, addr: tuple[str, int]) -> None:
        if self.remote_target is None:
            return
        if addr != self.remote_target:
            return

        try:
            packet = parse_rtp_packet(data)
            if packet.payload_type != RTP_PAYLOAD_TYPE_PCMU:
                raise RtpPacketError(f"unsupported payload type {packet.payload_type}")
            if len(packet.payload) != G711_ULAW_PAYLOAD_BYTES:
                raise RtpPacketError(f"expected {G711_ULAW_PAYLOAD_BYTES} bytes of G.711 payload")
            for payload in self._buffer_inbound_payload(packet):
                self._inbound_audio_queue.put_nowait(decode_ulaw_payload(payload))
        except RtpPacketError:
            self.inbound_telemetry.note_invalid_packet()

    def report_transport_failure(self, exc: Exception) -> None:
        if self._closed:
            return
        asyncio.create_task(self._fail(f"rtp_transport_failure: {exc}"))

    async def stop(self) -> None:
        if self._closed:
            return
        self._closed = True
        await self._close_runtime_and_tasks()
        await self._emit_telemetry()

    @property
    def instruction_override_text(self) -> str:
        return "\n\n".join(self._instruction_override_parts)

    async def interrupt(self) -> dict[str, Any]:
        if self._closed:
            raise RuntimeError("live RTP bridge is closed")
        self.outbound_stream.clear()
        await self.runtime_session.interrupt()
        return {}

    async def append_instructions(self, text: str) -> dict[str, Any]:
        if self._closed:
            raise RuntimeError("live RTP bridge is closed")
        normalized = text.strip()
        if not normalized:
            raise ValueError("instruction text is required")
        await self.runtime_session.append_instructions(normalized)
        self._instruction_override_parts.append(normalized)
        return {"instruction_override_text": self.instruction_override_text}

    async def request_response(self, prompt: str | None = None) -> dict[str, Any]:
        if self._closed:
            raise RuntimeError("live RTP bridge is closed")
        await self.runtime_session.request_response(prompt)
        return {"prompt": prompt} if prompt else {}

    async def _forward_inbound_audio(self) -> None:
        try:
            while not self._closed:
                audio = await self._inbound_audio_queue.get()
                await self.runtime_session.send_audio(audio)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self._fail(f"runtime_input_failure: {exc}")

    async def _forward_runtime_audio(self) -> None:
        try:
            while not self._closed:
                event = await self.runtime_session.receive_event()
                if event is None:
                    break
                await self._handle_runtime_event(event)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self._fail(f"runtime_output_failure: {exc}")

    async def _handle_runtime_event(self, event: RuntimeStreamEvent) -> None:
        if event.event_type == "clear":
            self.outbound_stream.clear()
            await self._handle_interruption(event.attributes.get("reason", "runtime_clear"))
            return
        if event.event_type == "audio" and event.audio is not None:
            self.outbound_stream.enqueue_audio(event.audio)
            return
        if event.event_type.startswith("conversation."):
            await self._handle_conversation_event(event)

    async def _handle_conversation_event(self, event: RuntimeStreamEvent) -> None:
        attributes = self._normalize_conversation_attributes(event)
        if attributes is None:
            return
        await self.hooks.on_conversation_event(event.event_type, attributes, event.transient)

    def _normalize_conversation_attributes(self, event: RuntimeStreamEvent) -> dict[str, Any] | None:
        speaker = event.attributes.get("speaker")
        if speaker == "user":
            turn_state = self._user_turn
            next_status = "listening"
        elif speaker == "assistant":
            turn_state = self._assistant_turn
            next_status = "responding"
        else:
            return dict(event.attributes)

        turn_id, turn_index = self._ensure_turn_state(
            turn_state,
            vendor_turn_id=event.attributes.get("vendor_turn_id"),
        )
        attributes = {
            **event.attributes,
            "speaker": speaker,
            "turn_id": turn_id,
            "turn_index": turn_index,
        }

        if event.event_type.endswith(".turn.started"):
            self._conversation_status = next_status
            return attributes

        if event.event_type.endswith(".transcript.delta"):
            delta = str(event.attributes.get("delta") or "")
            if delta:
                turn_state.text_parts.append(delta)
            self._conversation_status = next_status
            return attributes

        if event.event_type.endswith(".turn.completed"):
            text = str(event.attributes.get("text") or "").strip() or "".join(turn_state.text_parts).strip()
            if not text:
                turn_state.reset()
                self._conversation_status = "idle"
                return None
            attributes["text"] = text
            turn_state.reset()
            self._conversation_status = "idle"
            return attributes

        return attributes

    async def _handle_interruption(self, reason: str) -> None:
        if self._assistant_turn.turn_id is None and self._conversation_status != "responding":
            return
        attributes = {
            "reason": reason,
            "speaker": "assistant",
            "turn_id": self._assistant_turn.turn_id or f"assistant-turn-{self._conversation_turn_index}",
            "turn_index": self._assistant_turn.turn_index or self._conversation_turn_index,
        }
        self._assistant_turn.reset()
        self._conversation_status = "interrupted"
        await self.hooks.on_conversation_event("conversation.interruption", attributes, False)

    def _ensure_turn_state(
        self,
        turn_state: _ConversationTurnState,
        *,
        vendor_turn_id: str | None,
    ) -> tuple[str, int]:
        if turn_state.turn_id is not None and (vendor_turn_id is None or turn_state.vendor_turn_id == vendor_turn_id):
            return turn_state.turn_id, turn_state.turn_index

        turn_state.reset()
        self._conversation_turn_index += 1
        turn_state.turn_index = self._conversation_turn_index
        turn_state.turn_id = f"{turn_state.speaker}-turn-{turn_state.turn_index}"
        turn_state.vendor_turn_id = vendor_turn_id
        return turn_state.turn_id, turn_state.turn_index

    async def _sender_loop(self) -> None:
        loop = asyncio.get_running_loop()
        interval_seconds = G711_ULAW_FRAME_MS / 1000.0
        next_send_at = loop.time() + interval_seconds
        try:
            while not self._closed:
                sleep_for = next_send_at - loop.time()
                if sleep_for > 0:
                    await asyncio.sleep(sleep_for)
                now = loop.time()
                if self.transport is None or self.remote_target is None:
                    next_send_at = now + interval_seconds
                    continue
                packet = self.outbound_stream.next_packet()
                if packet is None:
                    next_send_at = max(next_send_at + interval_seconds, now + interval_seconds)
                    continue
                self.inbound_telemetry.note_sender_lag(max(0.0, now - next_send_at))
                self.transport.sendto(packet, self.remote_target)
                if not self._first_audio_emitted and self._started_at is not None:
                    self._first_audio_emitted = True
                    await self.hooks.on_first_audio(round((now - self._started_at) * 1000.0, 2))
                next_send_at += interval_seconds
                if next_send_at <= now:
                    next_send_at = now + interval_seconds
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self._fail(f"rtp_sender_failure: {exc}")

    def _buffer_inbound_payload(self, packet) -> list[bytes]:
        if self._expected_sequence_number is None:
            self._expected_sequence_number = packet.sequence_number

        assert self._expected_sequence_number is not None
        if packet.sequence_number in self._inbound_packet_buffer:
            self.inbound_telemetry.note_duplicate_packet()
            return []
        if _is_older_than_expected(packet.sequence_number, self._expected_sequence_number):
            self.inbound_telemetry.note_late_packet()
            return []

        self.inbound_telemetry.note_packet(packet)
        if packet.sequence_number != self._expected_sequence_number:
            self.inbound_telemetry.note_out_of_order_packet()
        self._inbound_packet_buffer[packet.sequence_number] = packet.payload
        self.inbound_telemetry.note_buffered_packets(len(self._inbound_packet_buffer))
        released = self._release_ready_inbound_payloads()
        self.inbound_telemetry.note_buffered_packets(len(self._inbound_packet_buffer))
        return released

    def _release_ready_inbound_payloads(self) -> list[bytes]:
        assert self._expected_sequence_number is not None
        ready_payloads: list[bytes] = []

        while self._expected_sequence_number in self._inbound_packet_buffer:
            ready_payloads.append(self._inbound_packet_buffer.pop(self._expected_sequence_number))
            self._expected_sequence_number = (self._expected_sequence_number + 1) & 0xFFFF

        if ready_payloads or len(self._inbound_packet_buffer) <= INBOUND_JITTER_BUFFER_PACKETS:
            return ready_payloads

        earliest_sequence = min(
            self._inbound_packet_buffer,
            key=lambda sequence_number: _sequence_delta(sequence_number, self._expected_sequence_number),
        )
        skipped_packets = _sequence_delta(earliest_sequence, self._expected_sequence_number)
        if skipped_packets > 0:
            self.inbound_telemetry.note_missing_packets(skipped_packets)
            self._expected_sequence_number = earliest_sequence

        while self._expected_sequence_number in self._inbound_packet_buffer:
            ready_payloads.append(self._inbound_packet_buffer.pop(self._expected_sequence_number))
            self._expected_sequence_number = (self._expected_sequence_number + 1) & 0xFFFF

        return ready_payloads

    async def _fail(self, reason: str) -> None:
        if self._failure_emitted:
            return
        self._failure_emitted = True
        self._closed = True
        await self._close_runtime_and_tasks()
        telemetry = self.runtime_session.telemetry()
        await self.hooks.on_telemetry(self.inbound_telemetry, telemetry)
        await self.hooks.on_failure(reason, self.inbound_telemetry, telemetry)

    async def _emit_telemetry(self) -> None:
        if self._telemetry_emitted:
            return
        self._telemetry_emitted = True
        await self.hooks.on_telemetry(self.inbound_telemetry, self.runtime_session.telemetry())

    async def _close_runtime_and_tasks(self) -> None:
        current_task = asyncio.current_task()
        tasks = list(self._tasks)
        self._tasks.clear()
        for task in tasks:
            if task is current_task:
                continue
            task.cancel()
        for task in tasks:
            if task is current_task:
                continue
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        with contextlib.suppress(Exception):
            await self.runtime_session.close()
        if self.transport is not None:
            self.transport.close()
            self.transport = None


def _is_older_than_expected(sequence_number: int, expected_sequence_number: int) -> bool:
    delta = _sequence_delta(expected_sequence_number, sequence_number)
    return 0 < delta < 0x8000
