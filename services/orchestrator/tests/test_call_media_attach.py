from __future__ import annotations

import mimir.orchestrator.main as orchestrator_main
import pytest
from mimir.orchestrator.datastore import InMemoryDatastore


class FakeMediaBridgeClient:
    def __init__(self) -> None:
        self.create_calls: list[dict] = []
        self.attach_calls: list[dict] = []
        self.terminate_calls: list[dict] = []
        self.conversation_calls: list[dict] = []

    async def create_session(self, payload: dict) -> dict:
        self.create_calls.append(payload)
        return {
            "session_id": "media-test",
            "bridge_session_id": "media-test",
            "status": "created",
            "rtp": {
                "local_address": "media-bridge",
                "local_port": 12042,
                "remote_address": "",
                "remote_port": 0,
            },
        }

    async def attach_media(self, session_id: str, idempotency_key: str, body: dict | None = None) -> dict:
        self.attach_calls.append({"session_id": session_id, "idempotency_key": idempotency_key, "body": body})
        return {
            "session_id": session_id,
            "bridge_session_id": session_id,
            "call_id": "call-ari-test",
            "status": "active",
            "rtp": {
                "local_address": "media-bridge",
                "local_port": 12042,
                "remote_address": "10.0.0.20",
                "remote_port": 18000,
            },
        }

    async def terminate_media(self, session_id: str, reason: str, idempotency_key: str) -> dict:
        self.terminate_calls.append({"session_id": session_id, "reason": reason, "idempotency_key": idempotency_key})
        return {"session_id": session_id, "status": "terminated", "reason": reason}

    async def send_conversation_command(self, session_id: str, payload: dict) -> dict:
        self.conversation_calls.append({"session_id": session_id, "payload": payload})
        return {
            "session_id": session_id,
            "command": payload["command"],
            "status": "applied",
            "instruction_override_text": payload.get("text"),
        }


@pytest.fixture
def orchestrator_state(monkeypatch: pytest.MonkeyPatch) -> FakeMediaBridgeClient:
    fake_media_client = FakeMediaBridgeClient()
    monkeypatch.setattr(orchestrator_main, "datastore", InMemoryDatastore())
    monkeypatch.setattr(orchestrator_main, "media_client", fake_media_client)
    monkeypatch.setattr(orchestrator_main, "event_bus", orchestrator_main.EventBus())
    orchestrator_main.CALL_STARTED_AT.clear()
    return fake_media_client


def _inbound_request(media_start_mode: str = "deferred") -> orchestrator_main.InboundCallRequest:
    return orchestrator_main.InboundCallRequest(
        call_id="call-ari-test",
        direction="inbound",
        adapter=orchestrator_main.AdapterReference(name="asterisk-ari", call_id="ari-channel-1"),
        participant=orchestrator_main.CallParticipant(caller="1000", callee="2001", called_extension="2001"),
        metadata={"ari_channel_id": "ari-channel-1"},
        rtp=orchestrator_main.RtpFlow(local_address="0.0.0.0", local_port=0, remote_address="", remote_port=0),
        media_start_mode=media_start_mode,
    )


@pytest.mark.asyncio
async def test_deferred_inbound_call_creates_media_without_starting_it(orchestrator_state: FakeMediaBridgeClient) -> None:
    response = await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")

    assert response["action"] == "accept"
    assert response["media_start_mode"] == "deferred"
    assert response["media_session_id"] == "media-test"
    assert response["bridge_rtp"]["local_address"] == "media-bridge"
    assert orchestrator_state.attach_calls == []
    assert orchestrator_state.create_calls[0]["metadata"]["adapter_name"] == "asterisk-ari"


@pytest.mark.asyncio
async def test_attach_call_media_late_binds_adapter_rtp(orchestrator_state: FakeMediaBridgeClient) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")

    response = await orchestrator_main.attach_call_media(
        "call-ari-test",
        orchestrator_main.AttachMediaRequest(
            remote_rtp=orchestrator_main.RemoteRtpEndpoint(address="10.0.0.20", port=18000),
        ),
        idempotency_key="rtp-ari-channel-1",
    )

    assert response["action"] == "attach"
    assert response["media_status"] == "active"
    assert response["bridge_rtp"]["remote_address"] == "10.0.0.20"
    assert orchestrator_state.attach_calls == [
        {
            "session_id": "media-test",
            "idempotency_key": "attach-rtp-ari-channel-1",
            "body": {"remote_rtp": {"address": "10.0.0.20", "port": 18000}},
        }
    ]


@pytest.mark.asyncio
async def test_conversation_command_is_forwarded_to_media_bridge(orchestrator_state: FakeMediaBridgeClient) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")

    response = await orchestrator_main.command_call_conversation(
        "call-ari-test",
        orchestrator_main.ConversationCommandPayload(
            command="append_instructions",
            text="Be brief.",
        ),
    )

    assert response["command"] == "append_instructions"
    assert response["status"] == "applied"
    assert orchestrator_state.conversation_calls == [
        {
            "session_id": "media-test",
            "payload": {"command": "append_instructions", "text": "Be brief."},
        }
    ]


@pytest.mark.asyncio
async def test_conversation_transient_events_are_not_persisted_but_update_projection(
    orchestrator_state: FakeMediaBridgeClient,
) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")
    queue = orchestrator_main.event_bus.subscribe()

    await orchestrator_main._handle_media_event_payload(
        {
            "event_id": "evt-transient",
            "event_type": "conversation.user.transcript.delta",
            "call_id": "call-ari-test",
            "bridge_session_id": "media-test",
            "media_session_id": "media-test",
            "occurred_at": "2026-04-09T10:00:00Z",
            "transient": True,
            "attributes": {
                "runtime": "openai-realtime",
                "speaker": "user",
                "turn_id": "user-turn-1",
                "turn_index": 1,
                "delta": "Hello ",
            },
        }
    )

    event = await queue.get()
    projection = await orchestrator_main.datastore.load_call_projection("call-ari-test")
    events = await orchestrator_main.datastore.load_call_events("call-ari-test")
    orchestrator_main.event_bus.unsubscribe(queue)

    assert event["event_type"] == "conversation.user.transcript.delta"
    assert event["transient"] is True
    assert projection is not None
    assert projection["conversation"]["status"] == "listening"
    assert projection["conversation"]["turn_index"] == 1
    assert all(stored["event_type"] != "conversation.user.transcript.delta" for stored in events)


@pytest.mark.asyncio
async def test_conversation_completed_turns_are_persisted_and_update_projection(
    orchestrator_state: FakeMediaBridgeClient,
) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")

    await orchestrator_main._handle_media_event_payload(
        {
            "event_id": "evt-turn",
            "event_type": "conversation.assistant.turn.completed",
            "call_id": "call-ari-test",
            "bridge_session_id": "media-test",
            "media_session_id": "media-test",
            "occurred_at": "2026-04-09T10:00:01Z",
            "transient": False,
            "attributes": {
                "runtime": "openai-realtime",
                "speaker": "assistant",
                "turn_id": "assistant-turn-2",
                "turn_index": 2,
                "text": "Hello from MIMIR.",
            },
        }
    )

    projection = await orchestrator_main.datastore.load_call_projection("call-ari-test")
    events = await orchestrator_main.datastore.load_call_events("call-ari-test")

    assert projection is not None
    assert projection["conversation"]["status"] == "idle"
    assert projection["conversation"]["latest_assistant_turn"]["text"] == "Hello from MIMIR."
    assert any(stored["event_type"] == "conversation.assistant.turn.completed" for stored in events)
