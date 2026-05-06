import math
import sys
from array import array

from mimir.mediabridge.audio import PcmAudio, chunk_pcm16, linear_resample_pcm16_mono, resample_pcm16_mono


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


def test_24khz_to_8khz_downsample_preserves_duration() -> None:
    original = _tone_pcm16(frequency_hz=1000, sample_rate_hz=24_000, duration_ms=100)

    result = resample_pcm16_mono(original, source_rate_hz=24_000, target_rate_hz=8_000)

    assert len(result) == 800 * 2


def test_24khz_to_8khz_downsample_preserves_low_frequency_energy() -> None:
    original = _tone_pcm16(frequency_hz=1000, sample_rate_hz=24_000, duration_ms=100)

    filtered = resample_pcm16_mono(original, source_rate_hz=24_000, target_rate_hz=8_000)
    linear = linear_resample_pcm16_mono(original, source_rate_hz=24_000, target_rate_hz=8_000)

    assert _rms(filtered) > _rms(linear) * 0.8


def test_24khz_to_8khz_downsample_attenuates_high_frequency_aliasing() -> None:
    original = _tone_pcm16(frequency_hz=6000, sample_rate_hz=24_000, duration_ms=100)

    filtered = resample_pcm16_mono(original, source_rate_hz=24_000, target_rate_hz=8_000)
    linear = linear_resample_pcm16_mono(original, source_rate_hz=24_000, target_rate_hz=8_000)

    assert _rms(filtered) < _rms(linear) * 0.6


def _tone_pcm16(frequency_hz: float, sample_rate_hz: int, duration_ms: int, amplitude: int = 12000) -> bytes:
    sample_count = int(sample_rate_hz * duration_ms / 1000)
    samples = array(
        "h",
        (
            int(round(amplitude * math.sin(2 * math.pi * frequency_hz * sample_index / sample_rate_hz)))
            for sample_index in range(sample_count)
        ),
    )
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def _rms(frames: bytes) -> float:
    samples = array("h")
    samples.frombytes(frames)
    if sys.byteorder != "little":
        samples.byteswap()
    if not samples:
        return 0.0
    return math.sqrt(sum(sample * sample for sample in samples) / len(samples))
