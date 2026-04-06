from __future__ import annotations

from app.runtimes import OpenAIRealtimeRuntime, RuntimeRequest, build_initial_response_prompt


def _request(*, greeting: str = "Hello from MIMIR.", initialisation: str | None = None) -> RuntimeRequest:
    return RuntimeRequest(
        runtime="openai-realtime",
        model_name="gpt-realtime-mini",
        voice="verse",
        instructions="You are a historical scientist.",
        greeting=greeting,
        initialisation=initialisation,
        vad_mode="server_vad",
    )


def test_build_initial_response_prompt_prefers_initialisation() -> None:
    request = _request(
        initialisation="Ring, ring. The phone is ringing. You pick it up and say: 'Hello there!'",
    )

    prompt = build_initial_response_prompt(request)

    assert prompt == "Ring, ring. The phone is ringing. You pick it up and say: 'Hello there!'"


def test_build_initial_response_prompt_falls_back_to_exact_greeting_instruction() -> None:
    request = _request(greeting="Hello from MIMIR.")

    prompt = build_initial_response_prompt(request)

    assert prompt == "You are answering an inbound phone call and should speak first. Start by saying this greeting exactly: Hello from MIMIR."


def test_openai_response_payload_uses_initialisation_prompt() -> None:
    runtime = OpenAIRealtimeRuntime()
    request = _request(initialisation="Ring, ring. Answer with a playful futuristic greeting.")

    payload = runtime._response_payload(request)

    assert payload == {"instructions": "Ring, ring. Answer with a playful futuristic greeting."}
