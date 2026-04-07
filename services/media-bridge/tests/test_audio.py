from mimir.mediabridge.audio import PcmAudio, chunk_pcm16, resample_pcm16_mono


def test_pcm_duration_ms() -> None:
    audio = PcmAudio(pcm16=b"\x00\x00" * 8000, sample_rate_hz=8000, channels=1)

    assert audio.duration_ms == 1000.0


def test_chunk_pcm16_uses_requested_duration() -> None:
    audio = PcmAudio(pcm16=b"\x00\x00" * 8000, sample_rate_hz=8000, channels=1)

    chunks = chunk_pcm16(audio, chunk_ms=100)

    assert len(chunks) == 10
    assert all(len(chunk) == 1600 for chunk in chunks)


def test_resample_pcm16_mono_changes_frame_count() -> None:
    original = b"\x00\x00" * 800

    result = resample_pcm16_mono(original, source_rate_hz=8000, target_rate_hz=16000)

    assert len(result) == len(original) * 2
