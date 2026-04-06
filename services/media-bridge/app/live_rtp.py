from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
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
    decode_ulaw_payload,
    parse_rtp_packet,
)
from .runtimes import RuntimeSession, RuntimeSessionTelemetry, RuntimeStreamEvent


@dataclass(slots=True)
class LiveRtpHooks:
    on_first_audio: Callable[[float], Awaitable[None]]
    on_telemetry: Callable[[RtpInboundTelemetryTracker, RuntimeSessionTelemetry], Awaitable[None]]
    on_failure: Callable[[str, RtpInboundTelemetryTracker, RuntimeSessionTelemetry], Awaitable[None]]


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
        self._tasks: list[asyncio.Task[Any]] = []
        self._closed = False
        self._failure_emitted = False
        self._telemetry_emitted = False
        self._first_audio_emitted = False
        self._started_at: float | None = None

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
            self.inbound_telemetry.note_packet(packet)
            self._inbound_audio_queue.put_nowait(decode_ulaw_payload(packet.payload))
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
            return
        if event.event_type == "audio" and event.audio is not None:
            self.outbound_stream.enqueue_audio(event.audio)

    async def _sender_loop(self) -> None:
        interval_seconds = G711_ULAW_FRAME_MS / 1000.0
        try:
            while not self._closed:
                await asyncio.sleep(interval_seconds)
                if self.transport is None or self.remote_target is None:
                    continue
                packet = self.outbound_stream.next_packet()
                if packet is None:
                    continue
                self.transport.sendto(packet, self.remote_target)
                if not self._first_audio_emitted and self._started_at is not None:
                    self._first_audio_emitted = True
                    await self.hooks.on_first_audio(round((asyncio.get_running_loop().time() - self._started_at) * 1000.0, 2))
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self._fail(f"rtp_sender_failure: {exc}")

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
