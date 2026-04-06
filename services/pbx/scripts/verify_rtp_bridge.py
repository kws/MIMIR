from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import uuid
import urllib.parse
from dataclasses import dataclass
from typing import Any

import httpx
import websockets

APP_NAME = "mimir-rtp-verifier"


@dataclass(slots=True)
class VerifierConfig:
    ari_base_url: str
    ari_ws_url: str
    ari_username: str
    ari_password: str
    media_bridge_url: str


class AriClient:
    def __init__(self, base_url: str, username: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.auth = (username, password)
        self.client = httpx.AsyncClient(timeout=10.0, auth=self.auth)

    async def close(self) -> None:
        await self.client.aclose()

    async def post(self, path: str, *, params: dict[str, str] | None = None, json_body: dict[str, Any] | None = None) -> dict[str, Any]:
        response = await self.client.post(f"{self.base_url}{path}", params=params, json=json_body)
        response.raise_for_status()
        return response.json() if response.content else {}

    async def get(self, path: str, *, params: dict[str, str] | None = None) -> dict[str, Any]:
        response = await self.client.get(f"{self.base_url}{path}", params=params)
        response.raise_for_status()
        return response.json() if response.content else {}

    async def delete(self, path: str) -> None:
        response = await self.client.delete(f"{self.base_url}{path}")
        if response.status_code not in {200, 204, 404}:
            response.raise_for_status()


async def main() -> None:
    parser = argparse.ArgumentParser(description="Verify the MIMIR RTP bridge against Asterisk ExternalMedia.")
    parser.add_argument("--ari-base-url", default="http://localhost:8088/ari")
    parser.add_argument("--ari-ws-url", default="ws://localhost:8088/ari/events")
    parser.add_argument("--ari-username", default="mimir")
    parser.add_argument("--ari-password", default="mimir")
    parser.add_argument("--media-bridge-url", default="http://localhost:8081")
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    args = parser.parse_args()

    config = VerifierConfig(
        ari_base_url=args.ari_base_url,
        ari_ws_url=args.ari_ws_url,
        ari_username=args.ari_username,
        ari_password=args.ari_password,
        media_bridge_url=args.media_bridge_url,
    )

    ari = AriClient(config.ari_base_url, config.ari_username, config.ari_password)
    media_client = httpx.AsyncClient(timeout=10.0)
    bridge_id: str | None = None
    external_channel_id: str | None = None
    media_session_id: str | None = None
    call_channel_id: str | None = None

    ws_query = urllib.parse.urlencode(
        {
            "app": APP_NAME,
            "api_key": f"{config.ari_username}:{config.ari_password}",
            "subscribeAll": "true",
        }
    )
    ws_url = f"{config.ari_ws_url}?{ws_query}"

    print("Waiting for a SIP call into extension 3001-3006.")
    print("Each verifier extension maps to scientist profiles 2001-2006 via Stasis.")

    try:
        async with websockets.connect(ws_url, max_size=None) as websocket:
            inbound_channel, stasis_args = await asyncio.wait_for(_wait_for_inbound_channel(websocket), timeout=args.timeout_seconds)
            call_channel_id = inbound_channel["id"]
            target_extension = stasis_args[0] if stasis_args else "2001"
            print(f"Received channel {call_channel_id} for target extension {target_extension}.")

            bridge_id = f"mimir-bridge-{uuid.uuid4()}"
            await ari.post("/bridges", params={"type": "mixing", "bridgeId": bridge_id})
            await ari.post(f"/bridges/{bridge_id}/addChannel", params={"channel": call_channel_id})

            media_session = await media_client.post(
                f"{config.media_bridge_url}/v1/media/sessions",
                json={
                    "call_id": f"ari-{call_channel_id}",
                    "direction": "inbound",
                    "participant": {
                        "caller": inbound_channel.get("caller", {}).get("number") or "ari-verifier",
                        "callee": target_extension,
                        "called_extension": target_extension,
                    },
                    "ai_profile": {
                        "model_name": "gpt-realtime-mini",
                        "voice": "verse",
                        "instructions": "You are a helpful historical scientist persona answering a phone call.",
                        "greeting": "Hello from the MIMIR RTP verifier.",
                        "vad_mode": "server_vad",
                    },
                    "media_settings": {
                        "input_codec": "g711_ulaw",
                        "output_codec": "g711_ulaw",
                        "sample_rate_hz": 8000,
                    },
                    "rtp": {
                        "local_address": "0.0.0.0",
                        "local_port": 0,
                        "remote_address": "",
                        "remote_port": 0,
                    },
                    "metadata": {"adapter_name": "asterisk-externalmedia-verifier"},
                },
            )
            media_session.raise_for_status()
            media_session_payload = media_session.json()
            media_session_id = media_session_payload["session_id"]
            local_rtp = media_session_payload["rtp"]
            print(f"Media bridge reserved RTP at {local_rtp['local_address']}:{local_rtp['local_port']}.")

            external_channel = await ari.post(
                "/channels/externalMedia",
                params={
                    "app": APP_NAME,
                    "external_host": f"{local_rtp['local_address']}:{local_rtp['local_port']}",
                    "format": "ulaw",
                    "encapsulation": "rtp",
                    "transport": "UDP",
                    "direction": "both",
                },
            )
            external_channel_id = external_channel["id"]
            await ari.post(f"/bridges/{bridge_id}/addChannel", params={"channel": external_channel_id})

            local_address = (await ari.get(f"/channels/{external_channel_id}/variable", params={"variable": "UNICASTRTP_LOCAL_ADDRESS"})).get(
                "value"
            )
            local_port = (await ari.get(f"/channels/{external_channel_id}/variable", params={"variable": "UNICASTRTP_LOCAL_PORT"})).get("value")
            if not local_address or not local_port:
                raise RuntimeError("Asterisk ExternalMedia did not expose UNICASTRTP_LOCAL_ADDRESS/PORT")

            start_response = await media_client.post(
                f"{config.media_bridge_url}/v1/media/sessions/{media_session_id}/start",
                headers={"Idempotency-Key": f"start-{call_channel_id}"},
                json={"remote_rtp": {"address": local_address, "port": int(local_port)}},
            )
            start_response.raise_for_status()
            print(f"Media session {media_session_id} is active. Speak into the call to verify duplex RTP.")

            await _wait_for_call_end(websocket, call_channel_id=call_channel_id)
            print("Call ended, cleaning up.")
    finally:
        if media_session_id is not None:
            with contextlib.suppress(Exception):
                await media_client.post(
                    f"{config.media_bridge_url}/v1/media/sessions/{media_session_id}/stop",
                    headers={"Idempotency-Key": f"stop-{media_session_id}"},
                    json={"reason": "verifier_complete"},
                )
        if external_channel_id is not None:
            with contextlib.suppress(Exception):
                await ari.delete(f"/channels/{external_channel_id}")
        if bridge_id is not None:
            with contextlib.suppress(Exception):
                await ari.delete(f"/bridges/{bridge_id}")
        await media_client.aclose()
        await ari.close()


async def _wait_for_inbound_channel(websocket: Any) -> tuple[dict[str, Any], list[str]]:
    while True:
        event = json.loads(await websocket.recv())
        if event.get("type") != "StasisStart":
            continue
        channel = event.get("channel") or {}
        channel_name = channel.get("name", "")
        if channel_name.startswith("UnicastRTP/"):
            continue
        return channel, event.get("args") or []


async def _wait_for_call_end(websocket: Any, *, call_channel_id: str) -> None:
    while True:
        event = json.loads(await websocket.recv())
        if event.get("type") == "StasisEnd" and (event.get("channel") or {}).get("id") == call_channel_id:
            return


if __name__ == "__main__":
    asyncio.run(main())
