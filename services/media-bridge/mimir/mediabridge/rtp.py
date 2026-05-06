from __future__ import annotations

import random
import socket
import sys
import time
from array import array
from dataclasses import dataclass, field
from typing import Literal

from .audio import PcmAudio

RTP_VERSION = 2
RTP_HEADER_SIZE = 12
RTP_PAYLOAD_TYPE_PCMU = 0
G711_ULAW_SAMPLE_RATE_HZ = 8_000
G711_ULAW_FRAME_MS = 20
G711_ULAW_PAYLOAD_BYTES = 160
SUPPORTED_PLAYOUT_STALE_POLICY = "drop_oldest"
SUPPORTED_PLAYOUT_UNDERRUN_POLICY = "no_send"
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


@dataclass(frozen=True, slots=True)
class RtpQualitySettings:
    playout_max_depth_ms: int = 1200
    playout_target_prefill_ms: int = 60
    playout_stale_policy: Literal["drop_oldest"] = SUPPORTED_PLAYOUT_STALE_POLICY
    playout_underrun_policy: Literal["no_send"] = SUPPORTED_PLAYOUT_UNDERRUN_POLICY
    inbound_jitter_buffer_packets: int = 3

    def __post_init__(self) -> None:
        if self.playout_max_depth_ms < G711_ULAW_FRAME_MS:
            raise ValueError(f"playout_max_depth_ms must be at least {G711_ULAW_FRAME_MS}")
        if not 0 <= self.playout_target_prefill_ms <= self.playout_max_depth_ms:
            raise ValueError("playout_target_prefill_ms must be between 0 and playout_max_depth_ms")
        if self.playout_stale_policy != SUPPORTED_PLAYOUT_STALE_POLICY:
            raise ValueError(f"playout_stale_policy must be {SUPPORTED_PLAYOUT_STALE_POLICY}")
        if self.playout_underrun_policy != SUPPORTED_PLAYOUT_UNDERRUN_POLICY:
            raise ValueError(f"playout_underrun_policy must be {SUPPORTED_PLAYOUT_UNDERRUN_POLICY}")
        if not 0 <= self.inbound_jitter_buffer_packets <= 50:
            raise ValueError("inbound_jitter_buffer_packets must be between 0 and 50")


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
    playout_depth_ms: float = 0.0
    playout_max_depth_ms: float = 0.0
    playout_enqueued_ms: float = 0.0
    playout_dropped_ms: float = 0.0
    playout_truncated_ms: float = 0.0
    playout_underruns: int = 0
    outbound_packets_sent: int = 0
    outbound_packet_spacing_ms_avg: float = 0.0
    outbound_packet_spacing_ms_max: float = 0.0


@dataclass(slots=True)
class RtpPlayoutBufferSnapshot:
    depth_ms: float = 0.0
    max_depth_ms: float = 0.0
    enqueued_ms: float = 0.0
    dropped_ms: float = 0.0
    truncated_ms: float = 0.0
    underruns: int = 0


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
    playout_depth_ms: float = 0.0
    playout_max_depth_ms: float = 0.0
    playout_enqueued_ms: float = 0.0
    playout_dropped_ms: float = 0.0
    playout_truncated_ms: float = 0.0
    playout_underruns: int = 0
    outbound_packets_sent: int = 0
    outbound_packet_spacing_total_ms: float = 0.0
    outbound_packet_spacing_samples: int = 0
    outbound_packet_spacing_ms_max: float = 0.0
    previous_outbound_sent_at: float | None = None

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

    def note_outbound_packet_sent(self, sent_at: float) -> None:
        self.outbound_packets_sent += 1
        if self.previous_outbound_sent_at is not None:
            spacing_ms = max(0.0, (sent_at - self.previous_outbound_sent_at) * 1000.0)
            self.outbound_packet_spacing_total_ms += spacing_ms
            self.outbound_packet_spacing_samples += 1
            self.outbound_packet_spacing_ms_max = max(self.outbound_packet_spacing_ms_max, spacing_ms)
        self.previous_outbound_sent_at = sent_at

    def note_playout_snapshot(self, snapshot: RtpPlayoutBufferSnapshot) -> None:
        self.playout_depth_ms = snapshot.depth_ms
        self.playout_max_depth_ms = snapshot.max_depth_ms
        self.playout_enqueued_ms = snapshot.enqueued_ms
        self.playout_dropped_ms = snapshot.dropped_ms
        self.playout_truncated_ms = snapshot.truncated_ms
        self.playout_underruns = snapshot.underruns

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
            playout_depth_ms=self.playout_depth_ms,
            playout_max_depth_ms=self.playout_max_depth_ms,
            playout_enqueued_ms=round(self.playout_enqueued_ms, 4),
            playout_dropped_ms=round(self.playout_dropped_ms, 4),
            playout_truncated_ms=round(self.playout_truncated_ms, 4),
            playout_underruns=self.playout_underruns,
            outbound_packets_sent=self.outbound_packets_sent,
            outbound_packet_spacing_ms_avg=round(self.outbound_packet_spacing_total_ms / self.outbound_packet_spacing_samples, 4)
            if self.outbound_packet_spacing_samples
            else 0.0,
            outbound_packet_spacing_ms_max=round(self.outbound_packet_spacing_ms_max, 4),
        )


@dataclass(slots=True)
class RtpPlayoutBuffer:
    sample_rate_hz: int = G711_ULAW_SAMPLE_RATE_HZ
    payload_bytes: int = G711_ULAW_PAYLOAD_BYTES
    max_depth_ms: int = 1200
    target_prefill_ms: int = 60
    stale_policy: Literal["drop_oldest"] = SUPPORTED_PLAYOUT_STALE_POLICY
    underrun_policy: Literal["no_send"] = SUPPORTED_PLAYOUT_UNDERRUN_POLICY
    _buffer: bytearray = field(default_factory=bytearray)
    _enqueued_ms: float = 0.0
    _dropped_ms: float = 0.0
    _truncated_ms: float = 0.0
    _underruns: int = 0

    def __post_init__(self) -> None:
        RtpQualitySettings(
            playout_max_depth_ms=self.max_depth_ms,
            playout_target_prefill_ms=self.target_prefill_ms,
            playout_stale_policy=self.stale_policy,
            playout_underrun_policy=self.underrun_policy,
        )

    @property
    def frame_duration_ms(self) -> float:
        return (self.payload_bytes / self.sample_rate_hz) * 1000.0

    @property
    def max_depth_bytes(self) -> int:
        max_frames = max(1, int(self.max_depth_ms / self.frame_duration_ms))
        return max_frames * self.payload_bytes

    def enqueue_audio(self, audio: PcmAudio) -> None:
        normalized = audio if audio.sample_rate_hz == self.sample_rate_hz else audio.resample(self.sample_rate_hz)
        if normalized.channels != 1:
            raise ValueError("RtpPlayoutBuffer expects mono PCM audio")

        encoded = pcm16_to_ulaw(normalized.pcm16)
        self._buffer.extend(encoded)
        self._enqueued_ms += self._bytes_to_ms(len(encoded))
        self._enforce_max_depth()

    def clear(self) -> None:
        self._buffer.clear()

    def depth_ms(self) -> float:
        return round(self._bytes_to_ms(len(self._buffer)), 4)

    def next_payload(self) -> bytes | None:
        if len(self._buffer) < self.payload_bytes:
            self._underruns += 1
            return None

        payload = bytes(self._buffer[: self.payload_bytes])
        del self._buffer[: self.payload_bytes]
        return payload

    def snapshot(self) -> RtpPlayoutBufferSnapshot:
        return RtpPlayoutBufferSnapshot(
            depth_ms=self.depth_ms(),
            max_depth_ms=float(self.max_depth_ms),
            enqueued_ms=round(self._enqueued_ms, 4),
            dropped_ms=round(self._dropped_ms, 4),
            truncated_ms=round(self._truncated_ms, 4),
            underruns=self._underruns,
        )

    def _enforce_max_depth(self) -> None:
        overflow_bytes = len(self._buffer) - self.max_depth_bytes
        if overflow_bytes <= 0:
            return

        frames_to_drop = max(1, (overflow_bytes + self.payload_bytes - 1) // self.payload_bytes)
        bytes_to_drop = min(len(self._buffer), frames_to_drop * self.payload_bytes)
        del self._buffer[:bytes_to_drop]
        self._dropped_ms += self._bytes_to_ms(bytes_to_drop)

    def _bytes_to_ms(self, byte_count: int) -> float:
        return (byte_count / self.sample_rate_hz) * 1000.0


@dataclass(slots=True)
class RtpOutboundStream:
    payload_type: int = RTP_PAYLOAD_TYPE_PCMU
    sample_rate_hz: int = G711_ULAW_SAMPLE_RATE_HZ
    payload_bytes: int = G711_ULAW_PAYLOAD_BYTES
    settings: RtpQualitySettings = field(default_factory=RtpQualitySettings)
    ssrc: int = field(default_factory=lambda: random.randint(1, 0xFFFFFFFF))
    _sequence_number: int = field(default_factory=lambda: random.randint(0, 0xFFFF))
    _timestamp: int = field(default_factory=lambda: random.randint(0, 0xFFFFFFFF))
    _playout_buffer: RtpPlayoutBuffer = field(init=False)

    def __post_init__(self) -> None:
        self._playout_buffer = RtpPlayoutBuffer(
            sample_rate_hz=self.sample_rate_hz,
            payload_bytes=self.payload_bytes,
            max_depth_ms=self.settings.playout_max_depth_ms,
            target_prefill_ms=self.settings.playout_target_prefill_ms,
            stale_policy=self.settings.playout_stale_policy,
            underrun_policy=self.settings.playout_underrun_policy,
        )

    def enqueue_audio(self, audio: PcmAudio) -> None:
        self._playout_buffer.enqueue_audio(audio)

    def clear(self) -> None:
        self._playout_buffer.clear()

    def next_packet(self) -> bytes | None:
        payload = self._playout_buffer.next_payload()
        if payload is None:
            return None

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

    def playout_snapshot(self) -> RtpPlayoutBufferSnapshot:
        return self._playout_buffer.snapshot()

    def playout_depth_ms(self) -> float:
        return self._playout_buffer.depth_ms()


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
