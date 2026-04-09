from __future__ import annotations

import random
import socket
import sys
import time
from array import array
from dataclasses import dataclass, field

from .audio import PcmAudio

RTP_VERSION = 2
RTP_HEADER_SIZE = 12
RTP_PAYLOAD_TYPE_PCMU = 0
G711_ULAW_SAMPLE_RATE_HZ = 8_000
G711_ULAW_FRAME_MS = 20
G711_ULAW_PAYLOAD_BYTES = 160
_ULAW_BIAS = 0x84
_ULAW_CLIP = 32635
_ULAW_SEGMENTS = (0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF)


class RtpPacketError(ValueError):
    """Raised when an RTP packet does not match the v1 bridge constraints."""


@dataclass(slots=True)
class RtpPacket:
    payload_type: int
    sequence_number: int
    timestamp: int
    ssrc: int
    payload: bytes
    marker: bool = False


@dataclass(slots=True)
class RtpSocketReservation:
    sock: socket.socket
    bind_address: str
    advertised_address: str
    local_port: int


@dataclass(slots=True)
class RtpTelemetrySnapshot:
    packet_loss_pct: float = 0.0
    jitter_ms: float = 0.0
    received_packets: int = 0
    invalid_packets: int = 0
    missing_packets: int = 0
    duplicate_packets: int = 0
    late_packets: int = 0
    out_of_order_packets: int = 0
    max_buffered_packets: int = 0
    sender_lag_ms_avg: float = 0.0
    sender_lag_ms_max: float = 0.0


def parse_rtp_packet(packet_bytes: bytes) -> RtpPacket:
    if len(packet_bytes) < RTP_HEADER_SIZE:
        raise RtpPacketError("RTP packet shorter than header size")

    first = packet_bytes[0]
    version = first >> 6
    has_padding = bool(first & 0x20)
    has_extension = bool(first & 0x10)
    csrc_count = first & 0x0F
    if version != RTP_VERSION:
        raise RtpPacketError(f"unsupported RTP version {version}")
    if has_padding or has_extension or csrc_count:
        raise RtpPacketError("RTP padding, extensions, and CSRC lists are not supported in v1")

    second = packet_bytes[1]
    payload_type = second & 0x7F
    marker = bool(second & 0x80)
    sequence_number = int.from_bytes(packet_bytes[2:4], "big")
    timestamp = int.from_bytes(packet_bytes[4:8], "big")
    ssrc = int.from_bytes(packet_bytes[8:12], "big")
    return RtpPacket(
        payload_type=payload_type,
        sequence_number=sequence_number,
        timestamp=timestamp,
        ssrc=ssrc,
        payload=packet_bytes[RTP_HEADER_SIZE:],
        marker=marker,
    )


def build_rtp_packet(packet: RtpPacket) -> bytes:
    first = RTP_VERSION << 6
    second = packet.payload_type & 0x7F
    if packet.marker:
        second |= 0x80
    return b"".join(
        (
            bytes((first, second)),
            packet.sequence_number.to_bytes(2, "big"),
            packet.timestamp.to_bytes(4, "big"),
            packet.ssrc.to_bytes(4, "big"),
            packet.payload,
        )
    )


def ulaw_to_pcm16(payload: bytes) -> bytes:
    samples = array("h", (_decode_ulaw_sample(value) for value in payload))
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def pcm16_to_ulaw(frames: bytes) -> bytes:
    samples = array("h")
    samples.frombytes(frames)
    if sys.byteorder != "little":
        samples.byteswap()
    return bytes(_encode_ulaw_sample(sample) for sample in samples)


@dataclass(slots=True)
class RtpInboundTelemetryTracker:
    sample_rate_hz: int = G711_ULAW_SAMPLE_RATE_HZ
    highest_sequence_number: int | None = None
    received_packets: int = 0
    invalid_packets: int = 0
    missing_packets: int = 0
    duplicate_packets: int = 0
    late_packets: int = 0
    out_of_order_packets: int = 0
    max_buffered_packets: int = 0
    sender_lag_total_ms: float = 0.0
    sender_lag_samples: int = 0
    sender_lag_ms_max: float = 0.0
    previous_transit: float | None = None
    jitter: float = 0.0

    def note_invalid_packet(self) -> None:
        self.invalid_packets += 1

    def note_duplicate_packet(self) -> None:
        self.duplicate_packets += 1

    def note_late_packet(self) -> None:
        self.late_packets += 1

    def note_out_of_order_packet(self) -> None:
        self.out_of_order_packets += 1

    def note_missing_packets(self, count: int) -> None:
        if count > 0:
            self.missing_packets += count

    def note_buffered_packets(self, count: int) -> None:
        self.max_buffered_packets = max(self.max_buffered_packets, count)

    def note_sender_lag(self, lag_seconds: float) -> None:
        lag_ms = max(0.0, lag_seconds * 1000.0)
        self.sender_lag_total_ms += lag_ms
        self.sender_lag_samples += 1
        self.sender_lag_ms_max = max(self.sender_lag_ms_max, lag_ms)

    def note_packet(self, packet: RtpPacket, received_at: float | None = None) -> None:
        arrival = received_at if received_at is not None else time.monotonic()
        self.received_packets += 1

        if self.highest_sequence_number is None or _sequence_is_newer(packet.sequence_number, self.highest_sequence_number):
            self.highest_sequence_number = packet.sequence_number

        arrival_rtp_units = arrival * self.sample_rate_hz
        transit = arrival_rtp_units - packet.timestamp
        if self.previous_transit is not None:
            deviation = transit - self.previous_transit
            self.jitter += (abs(deviation) - self.jitter) / 16.0
        self.previous_transit = transit

    def snapshot(self) -> RtpTelemetrySnapshot:
        expected_packets = self.received_packets + self.missing_packets
        packet_loss_pct = 0.0
        if expected_packets > 0:
            packet_loss_pct = round((self.missing_packets / expected_packets) * 100.0, 4)
        return RtpTelemetrySnapshot(
            packet_loss_pct=packet_loss_pct,
            jitter_ms=round((self.jitter / self.sample_rate_hz) * 1000.0, 4),
            received_packets=self.received_packets,
            invalid_packets=self.invalid_packets,
            missing_packets=self.missing_packets,
            duplicate_packets=self.duplicate_packets,
            late_packets=self.late_packets,
            out_of_order_packets=self.out_of_order_packets,
            max_buffered_packets=self.max_buffered_packets,
            sender_lag_ms_avg=round(self.sender_lag_total_ms / self.sender_lag_samples, 4) if self.sender_lag_samples else 0.0,
            sender_lag_ms_max=round(self.sender_lag_ms_max, 4),
        )


@dataclass(slots=True)
class RtpOutboundStream:
    payload_type: int = RTP_PAYLOAD_TYPE_PCMU
    sample_rate_hz: int = G711_ULAW_SAMPLE_RATE_HZ
    payload_bytes: int = G711_ULAW_PAYLOAD_BYTES
    ssrc: int = field(default_factory=lambda: random.randint(1, 0xFFFFFFFF))
    _sequence_number: int = field(default_factory=lambda: random.randint(0, 0xFFFF))
    _timestamp: int = field(default_factory=lambda: random.randint(0, 0xFFFFFFFF))
    _buffer: bytearray = field(default_factory=bytearray)

    def enqueue_audio(self, audio: PcmAudio) -> None:
        normalized = audio if audio.sample_rate_hz == self.sample_rate_hz else audio.resample(self.sample_rate_hz)
        if normalized.channels != 1:
            raise ValueError("RtpOutboundStream expects mono PCM audio")
        self._buffer.extend(pcm16_to_ulaw(normalized.pcm16))

    def clear(self) -> None:
        self._buffer.clear()

    def next_packet(self) -> bytes | None:
        if len(self._buffer) < self.payload_bytes:
            return None

        payload = bytes(self._buffer[: self.payload_bytes])
        del self._buffer[: self.payload_bytes]
        packet = RtpPacket(
            payload_type=self.payload_type,
            sequence_number=self._sequence_number,
            timestamp=self._timestamp,
            ssrc=self.ssrc,
            payload=payload,
        )
        self._sequence_number = (self._sequence_number + 1) & 0xFFFF
        self._timestamp = (self._timestamp + self.payload_bytes) & 0xFFFFFFFF
        return build_rtp_packet(packet)


def decode_ulaw_payload(payload: bytes) -> PcmAudio:
    return PcmAudio(pcm16=ulaw_to_pcm16(payload), sample_rate_hz=G711_ULAW_SAMPLE_RATE_HZ, channels=1)


def reserve_rtp_socket(
    bind_address: str,
    advertised_address: str,
    requested_port: int,
    port_range_start: int,
    port_range_end: int,
) -> RtpSocketReservation:
    if requested_port > 0:
        sock = _bind_socket(bind_address, requested_port)
        return RtpSocketReservation(
            sock=sock,
            bind_address=bind_address,
            advertised_address=advertised_address,
            local_port=sock.getsockname()[1],
        )

    if port_range_start <= 0 or port_range_end <= 0 or port_range_start > port_range_end:
        raise RuntimeError("invalid RTP port range configuration")

    last_error: OSError | None = None
    for port in range(port_range_start, port_range_end + 1):
        try:
            sock = _bind_socket(bind_address, port)
        except OSError as exc:
            last_error = exc
            continue
        return RtpSocketReservation(
            sock=sock,
            bind_address=bind_address,
            advertised_address=advertised_address,
            local_port=port,
        )

    raise RuntimeError("no RTP ports available in configured pool") from last_error


def close_socket_quietly(sock: socket.socket | None) -> None:
    if sock is None:
        return
    try:
        sock.close()
    except OSError:
        return


def _bind_socket(bind_address: str, port: int) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((bind_address, port))
        sock.setblocking(False)
    except OSError:
        sock.close()
        raise
    return sock


def _sequence_delta(current: int, previous: int) -> int:
    return (current - previous) & 0xFFFF


def _sequence_is_newer(current: int, previous: int) -> bool:
    delta = _sequence_delta(current, previous)
    return 0 < delta < 0x8000


def _encode_ulaw_sample(sample: int) -> int:
    sign = 0x80 if sample < 0 else 0
    magnitude = min(abs(sample), _ULAW_CLIP)
    magnitude += _ULAW_BIAS

    exponent = 0
    while exponent < len(_ULAW_SEGMENTS) and magnitude > _ULAW_SEGMENTS[exponent]:
        exponent += 1
    exponent = min(exponent, 7)
    mantissa = (magnitude >> (exponent + 3)) & 0x0F
    return (~(sign | (exponent << 4) | mantissa)) & 0xFF


def _decode_ulaw_sample(value: int) -> int:
    ulaw = (~value) & 0xFF
    sign = ulaw & 0x80
    exponent = (ulaw >> 4) & 0x07
    mantissa = ulaw & 0x0F
    magnitude = ((_ULAW_BIAS + (mantissa << 3)) << exponent) - _ULAW_BIAS
    return -magnitude if sign else magnitude
