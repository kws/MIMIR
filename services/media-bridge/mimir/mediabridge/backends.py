from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .audio import PcmAudio, load_wav, write_wav
from .controller_contract import (
    BridgeSessionStatus,
    CreateMediaSessionRequest,
    RemoteRtpEndpoint,
    RtpFlow,
)
from .live_rtp import LiveRtpBridge, LiveRtpHooks
from .rtp import RtpQualitySettings, RtpSocketReservation, close_socket_quietly, reserve_rtp_socket
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
class RtpRuntimeConfig:
    bind_address: str
    advertised_address: str
    port_range_start: int
    port_range_end: int


@dataclass(slots=True)
class BackendSession:
    session_id: str
    call_id: str
    request: CreateMediaSessionRequest
    rtp: RtpFlow
    rtp_reservation: RtpSocketReservation
    status: str = BridgeSessionStatus.CREATED.value
    reason: str | None = None
    runtime: str = OPENAI_RUNTIME
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    first_audio_at: float | None = None
    last_fixture_run: FixtureRunSummary | None = None
    live_bridge: LiveRtpBridge | None = None
    mode: str | None = None


class RuntimeMediaBackend:
    def __init__(
        self,
        runtime: LiveRuntime,
        artifact_root: Path,
        rtp_config: RtpRuntimeConfig,
        rtp_quality_settings: RtpQualitySettings,
    ) -> None:
        self.runtime = runtime
        self.runtime_name = runtime.runtime_name
        self.artifact_root = artifact_root
        self.rtp_config = rtp_config
        self.rtp_quality_settings = rtp_quality_settings

    def create(self, request: CreateMediaSessionRequest, session_id: str) -> BackendSession:
        reservation = reserve_rtp_socket(
            bind_address=self.rtp_config.bind_address,
            advertised_address=self.rtp_config.advertised_address,
            requested_port=request.rtp.local_port,
            port_range_start=self.rtp_config.port_range_start,
            port_range_end=self.rtp_config.port_range_end,
        )
        return BackendSession(
            session_id=session_id,
            call_id=request.call_id,
            request=request,
            runtime=self.runtime_name,
            rtp_reservation=reservation,
            rtp=RtpFlow(
                local_address=reservation.advertised_address,
                local_port=reservation.local_port,
                remote_address=request.rtp.remote_address,
                remote_port=request.rtp.remote_port,
            ),
        )

    async def start(
        self,
        session: BackendSession,
        *,
        live: bool,
        hooks: LiveRtpHooks | None = None,
        remote_rtp: RemoteRtpEndpoint | None = None,
    ) -> BackendSession:
        if session.status == BridgeSessionStatus.TERMINATED.value:
            return session

        if remote_rtp is not None:
            session.rtp = RtpFlow(
                local_address=session.rtp.local_address,
                local_port=session.rtp.local_port,
                remote_address=remote_rtp.address,
                remote_port=remote_rtp.port,
            )

        if live:
            if hooks is None:
                raise RuntimeError("live RTP start requires hooks")
            if session.mode == "fixture":
                raise RuntimeError("fixture sessions cannot be promoted to live RTP")
            if not session.rtp.has_remote_target():
                raise ValueError("remote RTP target is required to start live media")
            if session.live_bridge is None:
                runtime_session = await self.runtime.start_session(self._runtime_request(session))
                live_bridge = LiveRtpBridge(
                    reservation=session.rtp_reservation,
                    runtime_session=runtime_session,
                    hooks=hooks,
                    settings=self.rtp_quality_settings,
                )
                try:
                    await live_bridge.activate(session.rtp.remote_address, session.rtp.remote_port)
                    if session.request.ai_profile.greeting:
                        await runtime_session.request_greeting()
                except Exception:
                    await live_bridge.stop()
                    raise
                session.live_bridge = live_bridge
            session.mode = "live"
        else:
            session.mode = "fixture"

        session.status = BridgeSessionStatus.ACTIVE.value
        return session

    async def stop(self, session: BackendSession, reason: str) -> BackendSession:
        session.status = BridgeSessionStatus.TERMINATED.value
        session.reason = reason
        if session.live_bridge is not None:
            await session.live_bridge.stop()
            session.live_bridge = None
        close_socket_quietly(session.rtp_reservation.sock)
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

        runtime_request = self._runtime_request(session, include_greeting=include_greeting, vad_mode="manual")
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

    def _runtime_request(
        self,
        session: BackendSession,
        *,
        include_greeting: bool = False,
        vad_mode: str | None = None,
    ) -> RuntimeRequest:
        return RuntimeRequest(
            runtime=self.runtime_name,
            model_name=session.request.ai_profile.model_name,
            voice=session.request.ai_profile.voice,
            instructions=session.request.ai_profile.instructions,
            greeting=session.request.ai_profile.greeting,
            initialisation=session.request.ai_profile.initialisation,
            vad_mode=vad_mode or session.request.ai_profile.vad_mode,
            include_greeting=include_greeting,
        )


class BackendRouter:
    """Runtime selection for the provider-agnostic media bridge."""

    def __init__(self) -> None:
        artifact_root = Path(os.getenv("MEDIA_BRIDGE_ARTIFACT_ROOT", Path(__file__).resolve().parents[1] / "artifacts"))
        artifact_root.mkdir(parents=True, exist_ok=True)

        bind_address = os.getenv("MEDIA_BRIDGE_RTP_BIND_ADDRESS", "0.0.0.0")
        advertised_address_default = bind_address if bind_address not in {"0.0.0.0", "::"} else "127.0.0.1"
        rtp_config = RtpRuntimeConfig(
            bind_address=bind_address,
            advertised_address=os.getenv("MEDIA_BRIDGE_RTP_ADVERTISED_ADDRESS", advertised_address_default),
            port_range_start=int(os.getenv("MEDIA_BRIDGE_RTP_PORT_START", "12000")),
            port_range_end=int(os.getenv("MEDIA_BRIDGE_RTP_PORT_END", "12099")),
        )
        rtp_quality_settings = RtpQualitySettings(
            playout_max_depth_ms=_env_int("MEDIA_BRIDGE_PLAYOUT_MAX_DEPTH_MS", 1200),
            playout_target_prefill_ms=_env_int("MEDIA_BRIDGE_PLAYOUT_TARGET_PREFILL_MS", 60),
            playout_stale_policy=os.getenv("MEDIA_BRIDGE_PLAYOUT_STALE_POLICY", "drop_oldest"),
            playout_underrun_policy=os.getenv("MEDIA_BRIDGE_PLAYOUT_UNDERRUN_POLICY", "no_send"),
            inbound_jitter_buffer_packets=_env_int("MEDIA_BRIDGE_INBOUND_JITTER_BUFFER_PACKETS", 3),
        )

        self._backends = {
            OPENAI_RUNTIME: RuntimeMediaBackend(
                OpenAIRealtimeRuntime(),
                artifact_root=artifact_root,
                rtp_config=rtp_config,
                rtp_quality_settings=rtp_quality_settings,
            ),
            GEMINI_RUNTIME: RuntimeMediaBackend(
                GeminiLiveRuntime(),
                artifact_root=artifact_root,
                rtp_config=rtp_config,
                rtp_quality_settings=rtp_quality_settings,
            ),
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


def _env_int(name: str, default: int) -> int:
    return int(os.getenv(name, str(default)))
