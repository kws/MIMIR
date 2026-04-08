from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import sys
import urllib.parse
import uuid
from dataclasses import dataclass
from typing import Any

import httpx
import websockets

APP_NAME = "mimir-ari-adapter"
ADAPTER_NAME = "asterisk-ari"


@dataclass(slots=True)
class AdapterConfig:
    ari_base_url: str
    ari_ws_url: str
    ari_username: str
    ari_password: str
    orchestrator_url: str
    app_name: str
    adapter_name: str
    retry_delay_seconds: float


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


def _response_detail(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        return response.text.strip() or response.reason_phrase

    if isinstance(payload, dict):
        detail = payload.get("detail")
        if isinstance(detail, str) and detail.strip():
            return detail
        if detail is not None:
            return json.dumps(detail)
    return json.dumps(payload)


def _raise_for_status(response: httpx.Response, *, action: str) -> None:
    if response.is_success:
        return
    detail = _response_detail(response)
    raise RuntimeError(f"{action} failed with HTTP {response.status_code}: {detail}")


def _build_inbound_call_request(
    *,
    config: AdapterConfig,
    call_id: str,
    call_channel_id: str,
    inbound_channel: dict[str, Any],
    target_extension: str,
    bridge_id: str,
) -> dict[str, Any]:
    return {
        "call_id": call_id,
        "direction": "inbound",
        "adapter": {
            "name": config.adapter_name,
            "call_id": call_channel_id,
            "metadata": {"ari_app": config.app_name},
        },
        "participant": {
            "caller": inbound_channel.get("caller", {}).get("number") or "ari-adapter",
            "callee": target_extension,
            "called_extension": target_extension,
        },
        "metadata": {
            "ari_channel_id": call_channel_id,
            "asterisk_bridge_id": bridge_id,
        },
        "rtp": {
            "local_address": "0.0.0.0",
            "local_port": 0,
            "remote_address": "",
            "remote_port": 0,
        },
        "media_start_mode": "deferred",
    }


async def main() -> None:
    parser = argparse.ArgumentParser(description="Run an Asterisk ARI edge adapter through the MIMIR orchestrator.")
    parser.add_argument("--ari-base-url", default="http://localhost:8088/ari")
    parser.add_argument("--ari-ws-url", default="ws://localhost:8088/ari/events")
    parser.add_argument("--ari-username", default="mimir")
    parser.add_argument("--ari-password", default="mimir")
    parser.add_argument("--orchestrator-url", default="http://localhost:8080")
    parser.add_argument("--ari-app-name", default=APP_NAME)
    parser.add_argument("--adapter-name", default=ADAPTER_NAME)
    parser.add_argument("--timeout-seconds", type=float, default=0.0, help="Seconds to wait for a call; 0 waits forever.")
    parser.add_argument("--retry-delay-seconds", type=float, default=3.0)
    parser.add_argument("--once", action="store_true", help="Process one call and exit.")
    args = parser.parse_args()

    config = AdapterConfig(
        ari_base_url=args.ari_base_url,
        ari_ws_url=args.ari_ws_url,
        ari_username=args.ari_username,
        ari_password=args.ari_password,
        orchestrator_url=args.orchestrator_url.rstrip("/"),
        app_name=args.ari_app_name,
        adapter_name=args.adapter_name,
        retry_delay_seconds=args.retry_delay_seconds,
    )

    while True:
        try:
            await _run_once(config, timeout_seconds=args.timeout_seconds)
        except Exception as exc:
            if args.once:
                raise
            print(f"Adapter run failed: {exc}; retrying in {config.retry_delay_seconds:.1f}s.", file=sys.stderr)
            await asyncio.sleep(config.retry_delay_seconds)
            continue

        if args.once:
            return


async def _run_once(config: AdapterConfig, *, timeout_seconds: float) -> None:
    ari = AriClient(config.ari_base_url, config.ari_username, config.ari_password)
    orchestrator_client = httpx.AsyncClient(timeout=10.0)
    bridge_id: str | None = None
    external_channel_id: str | None = None
    call_channel_id: str | None = None
    call_id: str | None = None
    accepted_by_orchestrator = False

    ws_query = urllib.parse.urlencode(
        {
            "app": config.app_name,
            "api_key": f"{config.ari_username}:{config.ari_password}",
            "subscribeAll": "true",
        }
    )
    ws_url = f"{config.ari_ws_url}?{ws_query}"

    print("Waiting for a SIP call into the ARI adapter Stasis app.")
    print("The adapter will normalize the call through the orchestrator before attaching RTP.")

    try:
        async with websockets.connect(ws_url, max_size=None) as websocket:
            wait_for_inbound = _wait_for_inbound_channel(websocket)
            if timeout_seconds > 0:
                inbound_channel, stasis_args = await asyncio.wait_for(wait_for_inbound, timeout=timeout_seconds)
            else:
                inbound_channel, stasis_args = await wait_for_inbound
            call_channel_id = inbound_channel["id"]
            call_id = f"ari-{call_channel_id}"
            target_extension = stasis_args[0] if stasis_args else "2001"
            print(f"Received channel {call_channel_id} for target extension {target_extension}.")

            bridge_id = f"mimir-ari-{uuid.uuid4()}"
            await ari.post("/bridges", params={"type": "mixing", "bridgeId": bridge_id})
            await ari.post(f"/bridges/{bridge_id}/addChannel", params={"channel": call_channel_id})

            inbound_response = await orchestrator_client.post(
                f"{config.orchestrator_url}/v1/calls/inbound",
                headers={"Idempotency-Key": f"accept-{call_channel_id}"},
                json=_build_inbound_call_request(
                    config=config,
                    call_id=call_id,
                    call_channel_id=call_channel_id,
                    inbound_channel=inbound_channel,
                    target_extension=target_extension,
                    bridge_id=bridge_id,
                ),
            )
            _raise_for_status(inbound_response, action="accept inbound call")
            call_projection = inbound_response.json()
            if call_projection.get("action") == "reject":
                print(f"Orchestrator rejected {call_id}: {call_projection.get('reason', 'unknown')}")
                return
            accepted_by_orchestrator = True

            bridge_rtp = call_projection.get("bridge_rtp") or {}
            bridge_rtp_host = bridge_rtp.get("local_address")
            bridge_rtp_port = bridge_rtp.get("local_port")
            if not bridge_rtp_host or not bridge_rtp_port:
                raise RuntimeError("orchestrator did not return a bridge RTP endpoint")

            print(f"Orchestrator reserved media bridge RTP at {bridge_rtp_host}:{bridge_rtp_port}.")
            external_channel = await ari.post(
                "/channels/externalMedia",
                params={
                    "app": config.app_name,
                    "external_host": f"{bridge_rtp_host}:{bridge_rtp_port}",
                    "format": "ulaw",
                    "encapsulation": "rtp",
                    "transport": "UDP",
                    "direction": "both",
                },
            )
            external_channel_id = external_channel["id"]
            await ari.post(f"/bridges/{bridge_id}/addChannel", params={"channel": external_channel_id})

            local_address = (
                await ari.get(f"/channels/{external_channel_id}/variable", params={"variable": "UNICASTRTP_LOCAL_ADDRESS"})
            ).get("value")
            local_port = (await ari.get(f"/channels/{external_channel_id}/variable", params={"variable": "UNICASTRTP_LOCAL_PORT"})).get(
                "value"
            )
            if not local_address or not local_port:
                raise RuntimeError("Asterisk ExternalMedia did not expose UNICASTRTP_LOCAL_ADDRESS/PORT")

            attach_response = await orchestrator_client.post(
                f"{config.orchestrator_url}/v1/calls/{call_id}/media/attach",
                headers={"Idempotency-Key": f"rtp-{call_channel_id}"},
                json={"remote_rtp": {"address": local_address, "port": int(local_port)}},
            )
            _raise_for_status(attach_response, action=f"attach media for call {call_id}")
            print(f"Call {call_id} is active through the orchestrator. Speak into the call to verify duplex RTP.")

            await _wait_for_call_end(websocket, call_channel_id=call_channel_id)
            print("Call ended, notifying orchestrator and cleaning up.")
    finally:
        if call_id is not None and call_channel_id is not None and accepted_by_orchestrator:
            with contextlib.suppress(Exception):
                await orchestrator_client.post(
                    f"{config.orchestrator_url}/v1/calls/{call_id}/hangup",
                    headers={"Idempotency-Key": f"hangup-{call_channel_id}"},
                    json={"reason": "ari_channel_ended"},
                )
        if external_channel_id is not None:
            with contextlib.suppress(Exception):
                await ari.delete(f"/channels/{external_channel_id}")
        if bridge_id is not None:
            with contextlib.suppress(Exception):
                await ari.delete(f"/bridges/{bridge_id}")
        await orchestrator_client.aclose()
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
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as exc:
        print(f"ARI adapter failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
