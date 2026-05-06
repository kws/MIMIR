from __future__ import annotations

import math
import sys
import wave
from array import array
from dataclasses import dataclass
from pathlib import Path


@dataclass(slots=True)
class PcmAudio:
    pcm16: bytes
    sample_rate_hz: int
    channels: int = 1

    @property
    def duration_ms(self) -> float:
        if not self.pcm16 or self.sample_rate_hz <= 0:
            return 0.0
        sample_count = len(self.pcm16) // 2
        return (sample_count / self.sample_rate_hz) * 1000.0

    def resample(self, target_rate_hz: int) -> "PcmAudio":
        if self.sample_rate_hz == target_rate_hz:
            return self
        if self.channels != 1:
            raise ValueError("resample expects mono PCM audio")
        return PcmAudio(
            pcm16=resample_pcm16_mono(self.pcm16, self.sample_rate_hz, target_rate_hz),
            sample_rate_hz=target_rate_hz,
            channels=1,
        )


def load_wav(path: str | Path) -> PcmAudio:
    source = Path(path)
    with wave.open(str(source), "rb") as handle:
        channels = handle.getnchannels()
        sample_width = handle.getsampwidth()
        sample_rate_hz = handle.getframerate()
        frames = handle.readframes(handle.getnframes())

    if sample_width != 2:
        raise ValueError(f"{source} must be 16-bit PCM WAV audio")

    if channels > 1:
        frames = downmix_to_mono(frames, channels)
        channels = 1

    return PcmAudio(pcm16=frames, sample_rate_hz=sample_rate_hz, channels=channels)


def write_wav(path: str | Path, audio: PcmAudio) -> Path:
    if audio.channels != 1:
        raise ValueError("write_wav only supports mono PCM audio")

    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)

    with wave.open(str(target), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(audio.sample_rate_hz)
        handle.writeframes(audio.pcm16)

    return target


def chunk_pcm16(audio: PcmAudio, chunk_ms: int) -> list[bytes]:
    if chunk_ms <= 0:
        raise ValueError("chunk_ms must be positive")

    samples_per_chunk = max(1, math.ceil(audio.sample_rate_hz * (chunk_ms / 1000.0)))
    bytes_per_chunk = samples_per_chunk * 2
    return [audio.pcm16[index : index + bytes_per_chunk] for index in range(0, len(audio.pcm16), bytes_per_chunk)]


def downmix_to_mono(frames: bytes, channels: int) -> bytes:
    if channels == 1:
        return frames

    source = array("h")
    source.frombytes(frames)
    if sys.byteorder != "little":
        source.byteswap()

    mono = array("h")
    for index in range(0, len(source), channels):
        chunk = source[index : index + channels]
        mono.append(int(sum(chunk) / len(chunk)))

    if sys.byteorder != "little":
        mono.byteswap()
    return mono.tobytes()


def resample_pcm16_mono(frames: bytes, source_rate_hz: int, target_rate_hz: int) -> bytes:
    if source_rate_hz <= 0 or target_rate_hz <= 0:
        raise ValueError("sample rates must be positive")
    if source_rate_hz == target_rate_hz or not frames:
        return frames
    if source_rate_hz == 24_000 and target_rate_hz == 8_000:
        return downsample_24k_to_8k_pcm16_mono(frames)

    return linear_resample_pcm16_mono(frames, source_rate_hz, target_rate_hz)


def linear_resample_pcm16_mono(frames: bytes, source_rate_hz: int, target_rate_hz: int) -> bytes:
    if source_rate_hz <= 0 or target_rate_hz <= 0:
        raise ValueError("sample rates must be positive")
    if source_rate_hz == target_rate_hz or not frames:
        return frames

    source = array("h")
    source.frombytes(frames)
    if sys.byteorder != "little":
        source.byteswap()

    if len(source) == 1:
        result = array("h", [source[0]])
        if sys.byteorder != "little":
            result.byteswap()
        return result.tobytes()

    target_length = max(1, int(round(len(source) * target_rate_hz / source_rate_hz)))
    resampled = array("h")
    max_index = len(source) - 1

    for target_index in range(target_length):
        position = target_index * source_rate_hz / target_rate_hz
        left_index = min(int(position), max_index)
        right_index = min(left_index + 1, max_index)
        fraction = position - left_index
        left_sample = source[left_index]
        right_sample = source[right_index]
        interpolated = int(round(left_sample + (right_sample - left_sample) * fraction))
        resampled.append(interpolated)

    if sys.byteorder != "little":
        resampled.byteswap()
    return resampled.tobytes()


def downsample_24k_to_8k_pcm16_mono(frames: bytes) -> bytes:
    source = array("h")
    source.frombytes(frames)
    if sys.byteorder != "little":
        source.byteswap()

    if not source:
        return frames

    target_length = max(1, int(round(len(source) / 3)))
    filtered = array("h")
    max_index = len(source) - 1

    for target_index in range(target_length):
        center_index = min(target_index * 3, max_index)
        left = source[max(0, center_index - 1)]
        center = source[center_index]
        right = source[min(max_index, center_index + 1)]
        filtered.append(int(round((left + center + right) / 3)))

    if sys.byteorder != "little":
        filtered.byteswap()
    return filtered.tobytes()
