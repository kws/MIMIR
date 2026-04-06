from __future__ import annotations

import argparse
import asyncio
import json
import uuid

from .backends import BackendRouter
from .controller_contract import CallParticipant, CreateMediaSessionRequest, MediaSessionConfig, MediaSettings, RtpFlow


async def _run(args: argparse.Namespace) -> dict[str, object]:
    router = BackendRouter()
    runtime = args.runtime

    request = CreateMediaSessionRequest(
        call_id=args.call_id or f"fixture-{uuid.uuid4()}",
        direction="inbound",
        participant=CallParticipant(caller="fixture-caller", callee="fixture-target", called_extension=args.extension),
        ai_profile=MediaSessionConfig(
            model_name=args.model,
            voice=args.voice,
            instructions=args.instructions,
            greeting=args.greeting,
            initialisation=args.initialisation,
            vad_mode=args.vad_mode,
        ),
        media_settings=MediaSettings(input_codec="pcm16", output_codec="pcm16", sample_rate_hz=16_000),
        rtp=RtpFlow(local_address="127.0.0.1", local_port=0, remote_address="127.0.0.1", remote_port=0),
        metadata={"bridge_runtime": runtime},
    )

    backend = router.choose_backend(requested_runtime=runtime, model_name=request.ai_profile.model_name)
    session = backend.create(request, session_id=f"media-{uuid.uuid4()}")
    await backend.start(session, live=False)
    try:
        summary = await backend.run_fixture(
            session=session,
            fixture_path=args.fixture,
            output_wav_path=args.output,
            timeout_seconds=args.timeout_seconds,
            include_greeting=args.include_greeting,
        )
        return {
            "session_id": session.session_id,
            "call_id": session.call_id,
            "runtime": session.runtime,
            "fixture_path": summary.fixture_path,
            "output_wav_path": summary.output_wav_path,
            "output_sample_rate_hz": summary.output_sample_rate_hz,
            "input_duration_ms": summary.input_duration_ms,
            "output_duration_ms": summary.output_duration_ms,
            "first_audio_latency_ms": summary.first_audio_latency_ms,
            "input_transcript": summary.input_transcript,
            "output_transcript": summary.output_transcript,
            "vendor_session_id": summary.vendor_session_id,
            "debug_events": getattr(session.last_fixture_run, "debug_events", None),
        }
    finally:
        await backend.stop(session, reason="fixture_complete")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a prerecorded WAV fixture through the MIMIR media bridge runtime.")
    parser.add_argument("--runtime", choices=["openai-realtime", "gemini-live"], required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--voice", required=True)
    parser.add_argument("--instructions", required=True)
    parser.add_argument("--greeting", default="Hello from MIMIR.")
    parser.add_argument("--initialisation")
    parser.add_argument("--vad-mode", default="manual")
    parser.add_argument("--fixture")
    parser.add_argument("--output", required=True)
    parser.add_argument("--extension", default="2001")
    parser.add_argument("--call-id")
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    parser.add_argument("--include-greeting", action="store_true")
    args = parser.parse_args()

    if not args.fixture and not args.include_greeting:
        parser.error("either --fixture must be provided or --include-greeting must be set")

    result = asyncio.run(_run(args))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
