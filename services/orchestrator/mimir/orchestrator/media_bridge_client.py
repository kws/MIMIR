from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import httpx


class MediaBridgeClient:
    def __init__(self, base_url: str, timeout_seconds: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self._timeout = timeout_seconds

    async def create_session(self, payload: dict) -> dict:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(f"{self.base_url}/v1/media/sessions", json=payload)
            response.raise_for_status()
            return response.json()

    async def attach_media(self, session_id: str, idempotency_key: str, body: dict | None = None) -> dict:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                f"{self.base_url}/v1/media/sessions/{session_id}/start",
                json=body,
                headers={"Idempotency-Key": idempotency_key},
            )
            response.raise_for_status()
            return response.json()

    async def terminate_media(self, session_id: str, reason: str, idempotency_key: str) -> dict:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                f"{self.base_url}/v1/media/sessions/{session_id}/stop",
                json={"reason": reason},
                headers={"Idempotency-Key": idempotency_key},
            )
            response.raise_for_status()
            return response.json()

    async def send_conversation_command(self, session_id: str, payload: dict) -> dict:
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                f"{self.base_url}/v1/media/sessions/{session_id}/conversation/commands",
                json=payload,
            )
            response.raise_for_status()
            return response.json()

    async def stream_media_events(self) -> AsyncIterator[dict]:
        async with httpx.AsyncClient(timeout=None) as client:
            async with client.stream("GET", f"{self.base_url}/v1/media/events") as response:
                response.raise_for_status()
                event_type = None
                async for line in response.aiter_lines():
                    if not line:
                        event_type = None
                        continue
                    if line.startswith("event: "):
                        event_type = line.replace("event: ", "", 1)
                    elif line.startswith("data: "):
                        payload = line.replace("data: ", "", 1)
                        yield {"event_type": event_type, "payload": payload}
                    await asyncio.sleep(0)
