from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import os
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Any, Protocol

from .audio import PcmAudio, chunk_pcm16

OPENAI_RUNTIME = "openai-realtime"
GEMINI_RUNTIME = "gemini-live"
OPENAI_DEFAULT_MODEL = "gpt-realtime-mini"
GEMINI_DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
OPENAI_VOICE_NAMES = {"alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse"}


class RuntimeConfigurationError(RuntimeError):
    """Raised when a runtime is requested without the required configuration."""


@dataclass(slots=True)
class RuntimeRequest:
    runtime: str
    model_name: str
    voice: str
    instructions: str
    greeting: str
    vad_mode: str
    initialisation: str | None = None
    include_greeting: bool = False


def build_initial_response_prompt(request: RuntimeRequest) -> str | None:
    # Preserve the legacy "phone is ringing, now answer it" turn when available.
    if request.initialisation:
        return request.initialisation
    if request.greeting:
        return f"You are answering an inbound phone call and should speak first. Start by saying this greeting exactly: {request.greeting}"
    return None


@dataclass(slots=True)
class RuntimeRunResult:
    runtime: str
    model_name: str
    output_audio: PcmAudio
    vendor_session_id: str | None = None
    input_transcript: str | None = None
    output_transcript: str | None = None
    first_audio_latency_ms: float | None = None
    usage: dict[str, Any] = field(default_factory=dict)
    debug_events: list[dict[str, Any]] = field(default_factory=list)


@dataclass(slots=True)
class RuntimeSessionTelemetry:
    vendor_session_id: str | None = None
    ws_reconnects: int = 0
    ws_errors: int = 0


@dataclass(slots=True)
class RuntimeStreamEvent:
    event_type: str
    audio: PcmAudio | None = None


class RuntimeSession(Protocol):
    async def send_audio(self, audio_input: PcmAudio) -> None: ...

    async def request_greeting(self) -> None: ...

    async def receive_event(self) -> RuntimeStreamEvent | None: ...

    async def close(self) -> None: ...

    def telemetry(self) -> RuntimeSessionTelemetry: ...


class LiveRuntime(Protocol):
    runtime_name: str
    input_sample_rate_hz: int

    async def run_fixture(
        self,
        request: RuntimeRequest,
        input_audio: PcmAudio,
        timeout_seconds: float,
    ) -> RuntimeRunResult: ...

    async def start_session(self, request: RuntimeRequest) -> RuntimeSession: ...


class OpenAIRealtimeSession:
    def __init__(self, runtime: "OpenAIRealtimeRuntime", request: RuntimeRequest, websocket: Any) -> None:
        self.runtime = runtime
        self.request = request
        self.websocket = websocket
        self._events: asyncio.Queue[RuntimeStreamEvent | Exception | None] = asyncio.Queue()
        self._ready = asyncio.Event()
        self._closed = asyncio.Event()
        self._closing = False
        self._fatal_error: RuntimeError | None = None
        self._receiver_task: asyncio.Task[None] | None = None
        self._telemetry = RuntimeSessionTelemetry()

    @classmethod
    async def connect(cls, runtime: "OpenAIRealtimeRuntime", request: RuntimeRequest) -> "OpenAIRealtimeSession":
        websocket = await runtime._connect_websocket(model_name=request.model_name)
        session = cls(runtime=runtime, request=request, websocket=websocket)
        await websocket.send(json.dumps({"type": "session.update", "session": runtime._session_payload(request, continuous=True)}))
        session._receiver_task = asyncio.create_task(session._receiver_loop())
        try:
            await asyncio.wait_for(session._ready.wait(), timeout=10.0)
        except TimeoutError as exc:
            await session.close()
            raise TimeoutError("openai-realtime session did not become ready in time") from exc
        if session._fatal_error is not None:
            raise session._fatal_error
        return session

    async def send_audio(self, audio_input: PcmAudio) -> None:
        if self._fatal_error is not None:
            raise self._fatal_error
        audio_input = audio_input.resample(self.runtime.input_sample_rate_hz)
        for chunk in chunk_pcm16(audio_input, chunk_ms=20):
            await self.websocket.send(
                json.dumps(
                    {
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(chunk).decode("ascii"),
                    }
                )
            )

    async def request_greeting(self) -> None:
        if not self.request.greeting:
            return
        await self.websocket.send(
            json.dumps(
                {
                    "type": "response.create",
                    "response": self.runtime._response_payload(self.request),
                }
            )
        )

    async def receive_event(self) -> RuntimeStreamEvent | None:
        item = await self._events.get()
        if item is None:
            return None
        if isinstance(item, Exception):
            raise item
        return item

    async def close(self) -> None:
        if self._closing:
            await self._closed.wait()
            return

        self._closing = True
        with contextlib.suppress(Exception):
            await self.websocket.close()
        if self._receiver_task is not None:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._receiver_task
        self._closed.set()

    def telemetry(self) -> RuntimeSessionTelemetry:
        return RuntimeSessionTelemetry(
            vendor_session_id=self._telemetry.vendor_session_id,
            ws_reconnects=self._telemetry.ws_reconnects,
            ws_errors=self._telemetry.ws_errors,
        )

    async def _receiver_loop(self) -> None:
        try:
            while True:
                raw_message = await self.websocket.recv()
                message = json.loads(raw_message)
                event_type = message.get("type", "")

                if event_type == "session.created":
                    self._telemetry.vendor_session_id = ((message.get("session") or {}).get("id")) or self._telemetry.vendor_session_id
                    continue

                if event_type == "session.updated":
                    self._ready.set()
                    continue

                if event_type in {"response.output_audio.delta", "response.audio.delta"}:
                    delta = message.get("delta")
                    if delta:
                        await self._events.put(
                            RuntimeStreamEvent(
                                event_type="audio",
                                audio=PcmAudio(
                                    pcm16=base64.b64decode(delta),
                                    sample_rate_hz=self.runtime.output_sample_rate_hz,
                                    channels=1,
                                ),
                            )
                        )
                    continue

                if event_type in {"input_audio_buffer.speech_started", "response.cancelled", "response.interrupted"}:
                    await self._events.put(RuntimeStreamEvent(event_type="clear"))
                    continue

                if event_type == "error":
                    error = message.get("error") or {}
                    error_text = error.get("message") or json.dumps(message)
                    raise RuntimeError(f"{self.runtime.runtime_name} returned an error: {error_text}")
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if not self._closing:
                self._telemetry.ws_errors += 1
                self._fatal_error = RuntimeError(f"{self.runtime.runtime_name} live session failed: {exc}")
                await self._events.put(self._fatal_error)
                self._ready.set()
        finally:
            await self._events.put(None)
            self._closed.set()


class OpenAIRealtimeRuntime:
    runtime_name = OPENAI_RUNTIME
    input_sample_rate_hz = 24_000
    output_sample_rate_hz = 24_000

    def __init__(self, api_key_env: str = "OPENAI_API_KEY") -> None:
        self.api_key_env = api_key_env

    async def start_session(self, request: RuntimeRequest) -> RuntimeSession:
        self._api_key()
        return await OpenAIRealtimeSession.connect(runtime=self, request=request)

    async def run_fixture(
        self,
        request: RuntimeRequest,
        input_audio: PcmAudio,
        timeout_seconds: float,
    ) -> RuntimeRunResult:
        model_name = request.model_name or OPENAI_DEFAULT_MODEL
        audio_input = input_audio.resample(self.input_sample_rate_hz)
        output_audio_chunks: list[bytes] = []
        output_transcript_parts: list[str] = []
        input_transcript_parts: list[str] = []
        debug_events: list[dict[str, Any]] = []
        vendor_session_id: str | None = None
        response_id: str | None = None
        first_audio_latency_ms: float | None = None
        started_at = time.monotonic()

        websocket = await self._connect_websocket(model_name=model_name)
        try:
            await websocket.send(json.dumps({"type": "session.update", "session": self._session_payload(request, continuous=False)}))

            current_response_requested = False
            if input_audio.duration_ms > 0:
                await self._append_current_input_audio(websocket, audio_input)

            while True:
                try:
                    raw_message = await asyncio.wait_for(websocket.recv(), timeout=timeout_seconds)
                except TimeoutError as exc:
                    raise TimeoutError(f"{self.runtime_name} response timed out after {timeout_seconds} seconds") from exc

                message = json.loads(raw_message)
                event_type = message.get("type", "")
                if len(debug_events) < 50:
                    debug_events.append(_debug_event_snapshot(message))

                if event_type == "session.created":
                    vendor_session_id = ((message.get("session") or {}).get("id")) or vendor_session_id
                    continue

                if event_type == "session.updated":
                    if input_audio.duration_ms <= 0 and request.include_greeting and not current_response_requested:
                        await websocket.send(json.dumps({"type": "response.create", "response": self._response_payload(request)}))
                        current_response_requested = True
                    continue

                if event_type == "input_audio_buffer.committed":
                    if not current_response_requested:
                        await websocket.send(json.dumps({"type": "response.create", "response": self._response_payload(request)}))
                        current_response_requested = True
                    continue

                if event_type == "response.created":
                    response_id = ((message.get("response") or {}).get("id")) or response_id
                    continue

                if event_type in {"response.output_audio.delta", "response.audio.delta"}:
                    delta = message.get("delta")
                    if delta:
                        output_audio_chunks.append(base64.b64decode(delta))
                        if first_audio_latency_ms is None:
                            first_audio_latency_ms = round((time.monotonic() - started_at) * 1000.0, 2)
                    continue

                if event_type in {
                    "response.output_text.delta",
                    "response.audio_transcript.delta",
                    "response.output_audio_transcript.delta",
                }:
                    delta = message.get("delta")
                    if delta:
                        output_transcript_parts.append(delta)
                    continue

                if event_type in {"conversation.item.input_audio_transcription.delta", "input_audio_buffer.transcript.delta"}:
                    delta = message.get("delta")
                    if delta:
                        input_transcript_parts.append(delta)
                    continue

                if event_type in {"conversation.item.input_audio_transcription.completed", "input_audio_buffer.transcript.completed"}:
                    transcript = message.get("transcript")
                    if transcript:
                        input_transcript_parts.append(transcript)
                    continue

                if event_type == "response.done":
                    done_response = message.get("response") or {}
                    if response_id and done_response.get("id") and done_response.get("id") != response_id:
                        continue
                    break

                if event_type == "error":
                    error = message.get("error") or {}
                    error_text = error.get("message") or json.dumps(message)
                    raise RuntimeError(f"{self.runtime_name} returned an error: {error_text}")
        finally:
            with contextlib.suppress(Exception):
                await websocket.close()

        return RuntimeRunResult(
            runtime=self.runtime_name,
            model_name=model_name,
            output_audio=PcmAudio(
                pcm16=b"".join(output_audio_chunks),
                sample_rate_hz=self.output_sample_rate_hz,
                channels=1,
            ),
            vendor_session_id=vendor_session_id,
            input_transcript=_join_text(input_transcript_parts),
            output_transcript=_join_text(output_transcript_parts),
            first_audio_latency_ms=first_audio_latency_ms,
            debug_events=debug_events,
        )

    async def _connect_websocket(self, model_name: str | None) -> Any:
        try:
            import websockets
        except ImportError as exc:  # pragma: no cover - dependency is installed in the service image
            raise RuntimeConfigurationError("websockets is not installed") from exc

        resolved_model_name = model_name or OPENAI_DEFAULT_MODEL
        websocket_url = f"wss://api.openai.com/v1/realtime?model={urllib.parse.quote(resolved_model_name, safe='')}"
        return await websockets.connect(
            websocket_url,
            additional_headers={"Authorization": f"Bearer {self._api_key()}"},
            max_size=None,
            ping_interval=20,
            ping_timeout=20,
        )

    def _api_key(self) -> str:
        api_key = os.getenv(self.api_key_env)
        if not api_key:
            raise RuntimeConfigurationError(f"{self.api_key_env} is required for {self.runtime_name}")
        return api_key

    def _session_payload(self, request: RuntimeRequest, *, continuous: bool) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "type": "realtime",
            "instructions": request.instructions,
            "audio": {
                "input": {
                    "format": {"type": "audio/pcm", "rate": self.input_sample_rate_hz},
                    "turn_detection": None,
                },
                "output": {
                    "format": {"type": "audio/pcm", "rate": self.output_sample_rate_hz},
                    "voice": request.voice or "alloy",
                },
            },
            "output_modalities": ["audio"],
        }
        if request.vad_mode == "server_vad":
            turn_detection: dict[str, Any] = {"type": "server_vad"}
            if continuous:
                turn_detection.update(
                    {
                        "create_response": True,
                        "interrupt_response": True,
                        "prefix_padding_ms": 300,
                        "silence_duration_ms": 500,
                    }
                )
            payload["audio"]["input"]["turn_detection"] = turn_detection
        return payload

    def _response_payload(self, request: RuntimeRequest) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        prompt = build_initial_response_prompt(request)
        if prompt:
            payload["instructions"] = prompt
        return payload

    async def _append_current_input_audio(self, websocket: Any, audio_input: PcmAudio) -> None:
        for chunk in chunk_pcm16(audio_input, chunk_ms=100):
            await websocket.send(
                json.dumps(
                    {
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(chunk).decode("ascii"),
                    }
                )
            )

        await websocket.send(json.dumps({"type": "input_audio_buffer.commit"}))


class GeminiLiveRuntime:
    runtime_name = GEMINI_RUNTIME
    input_sample_rate_hz = 16_000
    output_sample_rate_hz = 24_000

    def __init__(self, api_key_env: str = "GEMINI_API_KEY") -> None:
        self.api_key_env = api_key_env

    async def start_session(self, request: RuntimeRequest) -> RuntimeSession:
        raise RuntimeConfigurationError(f"live RTP is not implemented for {self.runtime_name}")

    async def run_fixture(
        self,
        request: RuntimeRequest,
        input_audio: PcmAudio,
        timeout_seconds: float,
    ) -> RuntimeRunResult:
        try:
            from google import genai
            from google.genai import types
        except ImportError as exc:  # pragma: no cover - dependency is installed in the service image
            raise RuntimeConfigurationError("google-genai is not installed") from exc

        api_key = os.getenv(self.api_key_env)
        if not api_key:
            raise RuntimeConfigurationError(f"{self.api_key_env} is required for {self.runtime_name}")

        model_name = request.model_name or GEMINI_DEFAULT_MODEL
        audio_input = input_audio.resample(self.input_sample_rate_hz)
        config: dict[str, Any] = {
            "response_modalities": ["AUDIO"],
            "system_instruction": request.instructions,
            "realtime_input_config": {"automatic_activity_detection": {"disabled": True}},
            "input_audio_transcription": {},
            "output_audio_transcription": {},
        }

        voice_name = self._resolve_voice_name(request.voice)
        if voice_name:
            config["speech_config"] = {"voice_config": {"prebuilt_voice_config": {"voice_name": voice_name}}}

        client = genai.Client(api_key=api_key)
        output_audio_chunks: list[bytes] = []
        input_transcripts: list[str] = []
        response_text_parts: list[str] = []
        debug_events: list[dict[str, Any]] = []
        vendor_session_id: str | None = None
        output_sample_rate_hz = self.output_sample_rate_hz
        first_audio_latency_ms: float | None = None
        started_at = time.monotonic()

        async with client.aio.live.connect(model=model_name, config=config) as session:
            vendor_session_id = getattr(session, "id", None)
            if audio_input.duration_ms > 0:
                await session.send_realtime_input(activity_start=types.ActivityStart())
                await session.send_realtime_input(
                    audio=types.Blob(data=audio_input.pcm16, mime_type=f"audio/pcm;rate={self.input_sample_rate_hz}")
                )
                await session.send_realtime_input(activity_end=types.ActivityEnd())
            elif request.include_greeting and (request.initialisation or request.greeting):
                await self._send_greeting_turn(session=session, request=request, model_name=model_name)
            receiver = session.receive()

            while True:
                try:
                    response = await asyncio.wait_for(anext(receiver), timeout=timeout_seconds)
                except StopAsyncIteration:
                    break
                except TimeoutError as exc:
                    raise TimeoutError(f"{self.runtime_name} response timed out after {timeout_seconds} seconds") from exc
                if len(debug_events) < 50:
                    debug_events.append(_debug_event_snapshot(response))

                text_value = getattr(response, "text", None)
                if text_value:
                    response_text_parts.append(text_value)

                server_content = getattr(response, "server_content", None)
                if server_content is None:
                    continue

                input_transcription = getattr(server_content, "input_transcription", None)
                if input_transcription and getattr(input_transcription, "text", None):
                    input_transcripts.append(input_transcription.text)

                output_transcription = getattr(server_content, "output_transcription", None)
                if output_transcription and getattr(output_transcription, "text", None):
                    response_text_parts.append(output_transcription.text)

                model_turn = getattr(server_content, "model_turn", None)
                if model_turn and getattr(model_turn, "parts", None):
                    for part in model_turn.parts:
                        inline_data = getattr(part, "inline_data", None)
                        data = getattr(inline_data, "data", None) if inline_data else None
                        if data:
                            output_audio_chunks.append(data)
                            if first_audio_latency_ms is None:
                                first_audio_latency_ms = round((time.monotonic() - started_at) * 1000.0, 2)
                            output_sample_rate_hz = _sample_rate_from_mime_type(
                                getattr(inline_data, "mime_type", None),
                                default_rate_hz=output_sample_rate_hz,
                            )

                if getattr(server_content, "turn_complete", False):
                    break

        return RuntimeRunResult(
            runtime=self.runtime_name,
            model_name=model_name,
            output_audio=PcmAudio(
                pcm16=b"".join(output_audio_chunks),
                sample_rate_hz=output_sample_rate_hz,
                channels=1,
            ),
            vendor_session_id=vendor_session_id,
            input_transcript=_join_text(input_transcripts),
            output_transcript=_join_text(response_text_parts),
            first_audio_latency_ms=first_audio_latency_ms,
            debug_events=debug_events,
        )

    def _resolve_voice_name(self, requested_voice: str | None) -> str:
        if not requested_voice:
            return os.getenv("GEMINI_DEFAULT_VOICE", "Kore")
        if requested_voice.lower() in OPENAI_VOICE_NAMES:
            return os.getenv("GEMINI_DEFAULT_VOICE", "Kore")
        return requested_voice

    async def _send_greeting_turn(self, session: Any, request: RuntimeRequest, model_name: str) -> None:
        greeting_prompt = build_initial_response_prompt(request)
        if not greeting_prompt:
            return
        if model_name.startswith("gemini-2.5"):
            await session.send_client_content(
                turns={"role": "user", "parts": [{"text": greeting_prompt}]},
                turn_complete=True,
            )
            return
        await session.send_realtime_input(text=greeting_prompt)


def _sample_rate_from_mime_type(mime_type: str | None, default_rate_hz: int) -> int:
    if not mime_type:
        return default_rate_hz

    for part in mime_type.split(";")[1:]:
        key, _, value = part.partition("=")
        if key.strip().lower() == "rate":
            try:
                return int(value)
            except ValueError:
                return default_rate_hz
    return default_rate_hz


def _join_text(parts: list[str]) -> str | None:
    joined = "".join(parts).strip()
    return joined or None


def _debug_event_snapshot(message: Any) -> dict[str, Any]:
    if isinstance(message, dict):
        snapshot: dict[str, Any] = {"type": message.get("type", "unknown")}
        for key in ("response_id", "item_id", "output_index", "content_index"):
            if key in message:
                snapshot[key] = message[key]
        response = message.get("response")
        if isinstance(response, dict):
            if response.get("id"):
                snapshot["response.id"] = response["id"]
            if response.get("status"):
                snapshot["response.status"] = response["status"]
            status_details = response.get("status_details")
            if status_details:
                snapshot["response.status_details"] = status_details
        error = message.get("error")
        if error:
            snapshot["error"] = error
        if message.get("delta"):
            snapshot["delta_length"] = len(message["delta"])
        return snapshot

    snapshot = {"type": type(message).__name__}
    text_value = getattr(message, "text", None)
    if text_value:
        snapshot["text"] = text_value[:120]
    return snapshot
