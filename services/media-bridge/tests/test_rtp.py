from mimir.mediabridge.audio import PcmAudio
from mimir.mediabridge.rtp import (
    G711_ULAW_PAYLOAD_BYTES,
    RTP_PAYLOAD_TYPE_PCMU,
    RtpInboundTelemetryTracker,
    RtpOutboundStream,
    RtpPacket,
    build_rtp_packet,
    decode_ulaw_payload,
    parse_rtp_packet,
    pcm16_to_ulaw,
)


def test_rtp_packet_round_trip() -> None:
    packet = RtpPacket(
        payload_type=RTP_PAYLOAD_TYPE_PCMU,
        sequence_number=1234,
        timestamp=5678,
        ssrc=9012,
        payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
    )

    parsed = parse_rtp_packet(build_rtp_packet(packet))

    assert parsed.payload_type == packet.payload_type
    assert parsed.sequence_number == packet.sequence_number
    assert parsed.timestamp == packet.timestamp
    assert parsed.ssrc == packet.ssrc
    assert parsed.payload == packet.payload


def test_ulaw_codec_round_trip_keeps_frame_shape() -> None:
    pcm = PcmAudio(
        pcm16=b"".join((int((index % 32) * 500).to_bytes(2, "little", signed=True) for index in range(160))),
        sample_rate_hz=8000,
        channels=1,
    )

    encoded = pcm16_to_ulaw(pcm.pcm16)
    decoded = decode_ulaw_payload(encoded)

    assert len(encoded) == G711_ULAW_PAYLOAD_BYTES
    assert decoded.sample_rate_hz == 8000
    assert len(decoded.pcm16) == len(pcm.pcm16)


def test_rtp_inbound_telemetry_tracks_loss_and_invalid_packets() -> None:
    tracker = RtpInboundTelemetryTracker()
    tracker.note_invalid_packet()
    tracker.note_packet(
        RtpPacket(
            payload_type=RTP_PAYLOAD_TYPE_PCMU,
            sequence_number=10,
            timestamp=0,
            ssrc=1,
            payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
        ),
        received_at=0.0,
    )
    tracker.note_missing_packets(1)
    tracker.note_packet(
        RtpPacket(
            payload_type=RTP_PAYLOAD_TYPE_PCMU,
            sequence_number=12,
            timestamp=320,
            ssrc=1,
            payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
        ),
        received_at=0.04,
    )

    snapshot = tracker.snapshot()

    assert snapshot.invalid_packets == 1
    assert snapshot.received_packets == 2
    assert snapshot.missing_packets == 1
    assert snapshot.packet_loss_pct > 0


def test_rtp_inbound_telemetry_tracks_duplicates_late_packets_and_sender_lag() -> None:
    tracker = RtpInboundTelemetryTracker()
    tracker.note_packet(
        RtpPacket(
            payload_type=RTP_PAYLOAD_TYPE_PCMU,
            sequence_number=10,
            timestamp=0,
            ssrc=1,
            payload=b"\xff" * G711_ULAW_PAYLOAD_BYTES,
        ),
        received_at=0.0,
    )
    tracker.note_out_of_order_packet()
    tracker.note_duplicate_packet()
    tracker.note_late_packet()
    tracker.note_buffered_packets(3)
    tracker.note_sender_lag(0.0125)

    snapshot = tracker.snapshot()

    assert snapshot.out_of_order_packets == 1
    assert snapshot.duplicate_packets == 1
    assert snapshot.late_packets == 1
    assert snapshot.max_buffered_packets == 3
    assert snapshot.sender_lag_ms_avg == 12.5
    assert snapshot.sender_lag_ms_max == 12.5


def test_rtp_outbound_stream_packetizes_24khz_audio_into_ulaw_frames() -> None:
    stream = RtpOutboundStream()
    stream.enqueue_audio(PcmAudio(pcm16=b"\x00\x00" * 480, sample_rate_hz=24_000, channels=1))

    packet_bytes = stream.next_packet()
    packet = parse_rtp_packet(packet_bytes or b"")

    assert packet.payload_type == RTP_PAYLOAD_TYPE_PCMU
    assert len(packet.payload) == G711_ULAW_PAYLOAD_BYTES
