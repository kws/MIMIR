from __future__ import annotations

import mimir.orchestrator.main as orchestrator_main
import pytest
from mimir.orchestrator.datastore import InMemoryDatastore


class FakeMediaBridgeClient:
    def __init__(self) -> None:
        self.create_calls: list[dict] = []
        self.attach_calls: list[dict] = []
        self.terminate_calls: list[dict] = []

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


@pytest.fixture
def orchestrator_state(monkeypatch: pytest.MonkeyPatch) -> FakeMediaBridgeClient:
    fake_media_client = FakeMediaBridgeClient()
    monkeypatch.setattr(orchestrator_main, "datastore", InMemoryDatastore())
    monkeypatch.setattr(orchestrator_main, "media_client", fake_media_client)
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
