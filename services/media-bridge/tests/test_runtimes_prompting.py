from __future__ import annotations

import asyncio
import json

import pytest
from mimir.mediabridge.runtimes import (
    OpenAIRealtimeRuntime,
    OpenAIRealtimeSession,
    RuntimeRequest,
    build_initial_response_prompt,
)


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

    assert (
        prompt == "You are answering an inbound phone call and should speak first. Start by saying this greeting exactly: Hello from MIMIR."
    )


def test_openai_response_payload_uses_initialisation_prompt() -> None:
    runtime = OpenAIRealtimeRuntime()
    request = _request(initialisation="Ring, ring. Answer with a playful futuristic greeting.")

    payload = runtime._response_payload(request)

    assert payload == {"instructions": "Ring, ring. Answer with a playful futuristic greeting."}


class FakeWebSocket:
    def __init__(self, messages: list[dict]) -> None:
        self._messages: asyncio.Queue[str | None] = asyncio.Queue()
        for message in messages:
            self._messages.put_nowait(json.dumps(message))
        self.sent: list[dict] = []

    async def send(self, message: str) -> None:
        self.sent.append(json.loads(message))

    async def recv(self) -> str:
        message = await self._messages.get()
        if message is None:
            raise RuntimeError("websocket closed")
        return message

    async def close(self) -> None:
        await self._messages.put(None)


@pytest.mark.asyncio
async def test_openai_live_session_normalizes_conversation_events() -> None:
    websocket = FakeWebSocket(
        [
            {"type": "session.created", "session": {"id": "sess-1"}},
            {"type": "session.updated"},
            {"type": "input_audio_buffer.transcript.delta", "delta": "Hello ", "item_id": "user-1"},
            {"type": "input_audio_buffer.transcript.completed", "transcript": "Hello there", "item_id": "user-1"},
            {"type": "response.created", "response": {"id": "resp-1"}},
            {"type": "response.output_audio_transcript.delta", "delta": "Hi "},
            {"type": "response.output_audio_transcript.delta", "delta": "friend"},
            {"type": "response.done", "response": {"id": "resp-1"}},
            {"type": "input_audio_buffer.speech_started"},
        ]
    )
    runtime = OpenAIRealtimeRuntime()
    openai_session = OpenAIRealtimeSession(
        runtime=runtime,
        request=_request(),
        websocket=websocket,
    )
    receiver_task = asyncio.create_task(openai_session._receiver_loop())

    events = [await openai_session.receive_event() for _ in range(6)]
    await openai_session.close()
    await receiver_task

    assert [event.event_type for event in events] == [
        "conversation.user.transcript.delta",
        "conversation.user.turn.completed",
        "conversation.assistant.turn.started",
        "conversation.assistant.transcript.delta",
        "conversation.assistant.transcript.delta",
        "conversation.assistant.turn.completed",
    ]
    assert events[0].transient is True
    assert events[1].attributes["text"] == "Hello there"
    assert events[2].attributes["vendor_turn_id"] == "resp-1"
    assert events[5].attributes["text"] == "Hi friend"


@pytest.mark.asyncio
async def test_openai_live_session_command_methods_send_expected_messages() -> None:
    websocket = FakeWebSocket([])
    runtime = OpenAIRealtimeRuntime()
    openai_session = OpenAIRealtimeSession(
        runtime=runtime,
        request=_request(),
        websocket=websocket,
    )

    await openai_session.append_instructions("Be concise.")
    await openai_session.request_response("Summarize the last answer.")
    await openai_session.interrupt()

    assert websocket.sent[0]["type"] == "session.update"
    assert "Be concise." in websocket.sent[0]["session"]["instructions"]
    assert websocket.sent[1] == {
        "type": "response.create",
        "response": {"instructions": "Summarize the last answer."},
    }
    assert websocket.sent[2] == {"type": "response.cancel"}
