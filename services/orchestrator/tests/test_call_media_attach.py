from __future__ import annotations

import httpx
import mimir.orchestrator.main as orchestrator_main
import pytest
from mimir.orchestrator.conversation_supervisor import TOPIC_SWITCH_RULE_ID
from mimir.orchestrator.datastore import InMemoryDatastore


class FakeMediaBridgeClient:
    def __init__(self) -> None:
        self.create_calls: list[dict] = []
        self.attach_calls: list[dict] = []
        self.terminate_calls: list[dict] = []
        self.conversation_calls: list[dict] = []
        self.next_conversation_error: tuple[int, dict | str] | None = None

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
        if self.next_conversation_error is not None:
            status_code, detail = self.next_conversation_error
            self.next_conversation_error = None
            request = httpx.Request("POST", f"http://media-bridge.test/v1/media/sessions/{session_id}/conversation/commands")
            response = httpx.Response(status_code=status_code, json=detail, request=request)
            raise httpx.HTTPStatusError("conversation command rejected", request=request, response=response)
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


def _completed_turn_event(
    *,
    event_id: str,
    speaker: str,
    turn_id: str,
    turn_index: int,
    text: str,
) -> dict:
    return {
        "event_id": event_id,
        "event_type": f"conversation.{speaker}.turn.completed",
        "call_id": "call-ari-test",
        "bridge_session_id": "media-test",
        "media_session_id": "media-test",
        "occurred_at": "2026-04-09T10:00:01Z",
        "transient": False,
        "attributes": {
            "runtime": "openai-realtime",
            "speaker": speaker,
            "turn_id": turn_id,
            "turn_index": turn_index,
            "text": text,
        },
    }


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


@pytest.mark.asyncio
async def test_mimir_pivot_steering_sends_append_instructions(orchestrator_state: FakeMediaBridgeClient) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")

    await orchestrator_main._handle_media_event_payload(
        _completed_turn_event(
            event_id="evt-pivot",
            speaker="user",
            turn_id="user-turn-pivot",
            turn_index=3,
            text="We have covered enough about batteries. MIMIR PIVOT please.",
        )
    )

    assert orchestrator_state.conversation_calls == [
        {
            "session_id": "media-test",
            "payload": {
                "command": "append_instructions",
                "text": (
                    "The caller used the MIMIR pivot phrase. On your next turn, pivot away from the previous topic and ask what "
                    "they would like to discuss next."
                ),
            },
        }
    ]

    projection = await orchestrator_main.datastore.load_call_projection("call-ari-test")
    events = await orchestrator_main.datastore.load_call_events("call-ari-test")
    steering_events = [event for event in events if event["event_type"] == "conversation.steering.decision"]

    assert projection is not None
    assert steering_events[0]["attributes"]["rule_id"] == TOPIC_SWITCH_RULE_ID
    assert steering_events[0]["attributes"]["outcome"] == "sent"
    assert steering_events[0]["attributes"]["command_sent"] is True
    assert projection["conversation"]["latest_steering_decision"]["decision_key"] == (
        f"call-ari-test:user-turn-pivot:{TOPIC_SWITCH_RULE_ID}:append_instructions"
    )
    assert projection["conversation"]["latest_steering_decision"]["outcome"] == "sent"


@pytest.mark.asyncio
async def test_steering_command_rejection_is_audited(orchestrator_state: FakeMediaBridgeClient) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")
    orchestrator_state.next_conversation_error = (409, {"detail": "conversation commands require an active live session"})

    await orchestrator_main._handle_media_event_payload(
        _completed_turn_event(
            event_id="evt-rejected-pivot",
            speaker="user",
            turn_id="user-turn-rejected-pivot",
            turn_index=3,
            text="MIMIR PIVOT",
        )
    )

    events = await orchestrator_main.datastore.load_call_events("call-ari-test")
    steering_event = next(event for event in events if event["event_type"] == "conversation.steering.decision")

    assert len(orchestrator_state.conversation_calls) == 1
    assert steering_event["attributes"]["outcome"] == "rejected"
    assert steering_event["attributes"]["command_sent"] is True
    assert steering_event["attributes"]["error_status"] == 409
    assert steering_event["attributes"]["error_detail"] == {"detail": "conversation commands require an active live session"}


@pytest.mark.asyncio
async def test_duplicate_steering_decision_is_suppressed(orchestrator_state: FakeMediaBridgeClient) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")
    payload = _completed_turn_event(
        event_id="evt-duplicate-pivot",
        speaker="user",
        turn_id="user-turn-duplicate-pivot",
        turn_index=3,
        text="Can we try MIMIR PIVOT now?",
    )

    await orchestrator_main._handle_media_event_payload(payload)
    await orchestrator_main._handle_media_event_payload(payload)

    events = await orchestrator_main.datastore.load_call_events("call-ari-test")
    steering_events = [event for event in events if event["event_type"] == "conversation.steering.decision"]

    assert len(orchestrator_state.conversation_calls) == 1
    assert [event["attributes"]["outcome"] for event in steering_events] == ["sent", "suppressed"]
    assert steering_events[1]["attributes"]["command_sent"] is False


@pytest.mark.asyncio
async def test_conversation_command_lifecycle_events_update_projection(orchestrator_state: FakeMediaBridgeClient) -> None:
    await orchestrator_main.create_inbound_call(_inbound_request(), idempotency_key="accept-ari-channel-1")

    await orchestrator_main._handle_media_event_payload(
        {
            "event_id": "evt-command-requested",
            "event_type": "conversation.command.requested",
            "call_id": "call-ari-test",
            "bridge_session_id": "media-test",
            "media_session_id": "media-test",
            "occurred_at": "2026-04-09T10:00:02Z",
            "transient": False,
            "attributes": {"command": "append_instructions", "text": "Be brief."},
        }
    )
    projection = await orchestrator_main.datastore.load_call_projection("call-ari-test")
    assert projection is not None
    assert projection["conversation"]["latest_command"]["status"] == "requested"
    assert projection["conversation"]["latest_command"]["text"] == "Be brief."
    assert projection["conversation"]["instruction_override_text"] == ""

    await orchestrator_main._handle_media_event_payload(
        {
            "event_id": "evt-command-applied",
            "event_type": "conversation.command.applied",
            "call_id": "call-ari-test",
            "bridge_session_id": "media-test",
            "media_session_id": "media-test",
            "occurred_at": "2026-04-09T10:00:03Z",
            "transient": False,
            "attributes": {
                "command": "append_instructions",
                "text": "Be brief.",
                "instruction_override_text": "Be brief.",
            },
        }
    )
    projection = await orchestrator_main.datastore.load_call_projection("call-ari-test")
    assert projection is not None
    assert projection["conversation"]["latest_command"]["status"] == "applied"
    assert projection["conversation"]["instruction_override_text"] == "Be brief."

    await orchestrator_main._handle_media_event_payload(
        {
            "event_id": "evt-command-failed",
            "event_type": "conversation.command.failed",
            "call_id": "call-ari-test",
            "bridge_session_id": "media-test",
            "media_session_id": "media-test",
            "occurred_at": "2026-04-09T10:00:04Z",
            "transient": False,
            "attributes": {"command": "request_response", "prompt": "Recap.", "reason": "command_rejected"},
        }
    )
    projection = await orchestrator_main.datastore.load_call_projection("call-ari-test")

    assert projection is not None
    assert projection["conversation"]["latest_command"]["status"] == "failed"
    assert projection["conversation"]["latest_command"]["reason"] == "command_rejected"
    assert projection["conversation"]["instruction_override_text"] == "Be brief."
