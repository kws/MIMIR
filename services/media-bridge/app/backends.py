from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .audio import PcmAudio, load_wav, write_wav
from .controller_contract import BridgeSessionStatus, CreateMediaSessionRequest
from .runtimes import (
    GEMINI_RUNTIME,
    OPENAI_RUNTIME,
    GeminiLiveRuntime,
    LiveRuntime,
    OpenAIRealtimeRuntime,
    RuntimeConfigurationError,
    RuntimeRequest,
)


@dataclass(slots=True)
class FixtureRunSummary:
    fixture_path: str | None
    output_wav_path: str
    input_duration_ms: float
    output_duration_ms: float
    output_sample_rate_hz: int
    input_transcript: str | None = None
    output_transcript: str | None = None
    first_audio_latency_ms: float | None = None
    vendor_session_id: str | None = None
    debug_events: list[dict[str, object]] = field(default_factory=list)
    completed_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


@dataclass(slots=True)
class BackendSession:
    session_id: str
    call_id: str
    request: CreateMediaSessionRequest
    status: str = BridgeSessionStatus.CREATED.value
    reason: str | None = None
    runtime: str = OPENAI_RUNTIME
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    first_audio_at: float | None = None
    last_fixture_run: FixtureRunSummary | None = None


class RuntimeMediaBackend:
    def __init__(self, runtime: LiveRuntime, artifact_root: Path) -> None:
        self.runtime = runtime
        self.runtime_name = runtime.runtime_name
        self.artifact_root = artifact_root

    def create(self, request: CreateMediaSessionRequest, session_id: str) -> BackendSession:
        return BackendSession(
            session_id=session_id,
            call_id=request.call_id,
            request=request,
            runtime=self.runtime_name,
        )

    def start(self, session: BackendSession) -> BackendSession:
        if session.status != BridgeSessionStatus.TERMINATED.value:
            session.status = BridgeSessionStatus.ACTIVE.value
        return session

    def stop(self, session: BackendSession, reason: str) -> BackendSession:
        session.status = BridgeSessionStatus.TERMINATED.value
        session.reason = reason
        return session

    async def run_fixture(
        self,
        session: BackendSession,
        fixture_path: str | None,
        output_wav_path: str | None,
        timeout_seconds: float,
        include_greeting: bool,
    ) -> FixtureRunSummary:
        if session.status == BridgeSessionStatus.TERMINATED.value:
            raise RuntimeError("cannot run a fixture against a terminated media session")

        if fixture_path:
            fixture_audio = load_wav(fixture_path)
            resolved_fixture_path = str(Path(fixture_path).resolve())
        else:
            fixture_audio = PcmAudio(pcm16=b"", sample_rate_hz=self.runtime.input_sample_rate_hz, channels=1)
            resolved_fixture_path = None

        if fixture_audio.duration_ms <= 0 and not include_greeting:
            raise RuntimeError("fixture runs without input audio must enable include_greeting")

        runtime_request = RuntimeRequest(
            runtime=self.runtime_name,
            model_name=session.request.ai_profile.model_name,
            voice=session.request.ai_profile.voice,
            instructions=session.request.ai_profile.instructions,
            greeting=session.request.ai_profile.greeting,
            # Fixture playback is a single prerecorded turn, so explicit/manual turn
            # handling is more reliable than inheriting live-call server VAD.
            vad_mode="manual",
            include_greeting=include_greeting,
        )

        runtime_result = await self.runtime.run_fixture(
            request=runtime_request,
            input_audio=fixture_audio,
            timeout_seconds=timeout_seconds,
        )

        if runtime_result.output_audio.duration_ms <= 0:
            raise RuntimeError(f"{self.runtime_name} returned no audio for fixture {fixture_path}")

        artifact_path = Path(output_wav_path) if output_wav_path else self.artifact_root / f"{session.session_id}-{self.runtime_name}.wav"
        write_wav(artifact_path, runtime_result.output_audio)

        summary = FixtureRunSummary(
            fixture_path=resolved_fixture_path,
            output_wav_path=str(artifact_path.resolve()),
            input_duration_ms=round(fixture_audio.duration_ms, 2),
            output_duration_ms=round(runtime_result.output_audio.duration_ms, 2),
            output_sample_rate_hz=runtime_result.output_audio.sample_rate_hz,
            input_transcript=runtime_result.input_transcript,
            output_transcript=runtime_result.output_transcript,
            first_audio_latency_ms=runtime_result.first_audio_latency_ms,
            vendor_session_id=runtime_result.vendor_session_id,
            debug_events=runtime_result.debug_events,
        )
        session.last_fixture_run = summary
        return summary


class BackendRouter:
    """Runtime selection for the provider-agnostic media bridge."""

    def __init__(self) -> None:
        artifact_root = Path(os.getenv("MEDIA_BRIDGE_ARTIFACT_ROOT", Path(__file__).resolve().parents[1] / "artifacts"))
        artifact_root.mkdir(parents=True, exist_ok=True)
        self._backends = {
            OPENAI_RUNTIME: RuntimeMediaBackend(OpenAIRealtimeRuntime(), artifact_root=artifact_root),
            GEMINI_RUNTIME: RuntimeMediaBackend(GeminiLiveRuntime(), artifact_root=artifact_root),
        }
        self.default_runtime = os.getenv("MEDIA_BRIDGE_DEFAULT_RUNTIME", OPENAI_RUNTIME)

    def available_runtimes(self) -> list[str]:
        return sorted(self._backends)

    def choose_backend(self, requested_runtime: str | None = None, model_name: str | None = None) -> RuntimeMediaBackend:
        runtime_name = self._resolve_runtime_name(requested_runtime=requested_runtime, model_name=model_name)
        backend = self._backends.get(runtime_name)
        if backend is None:
            supported = ", ".join(sorted(self._backends))
            raise RuntimeConfigurationError(f"unsupported runtime '{runtime_name}', expected one of: {supported}")
        return backend

    def _resolve_runtime_name(self, requested_runtime: str | None, model_name: str | None) -> str:
        if requested_runtime:
            return requested_runtime

        if model_name:
            model_name_lower = model_name.lower()
            if model_name_lower.startswith("gemini-"):
                return GEMINI_RUNTIME
            if model_name_lower.startswith("gpt-") or model_name_lower.startswith("o"):
                return OPENAI_RUNTIME

        return self.default_runtime
