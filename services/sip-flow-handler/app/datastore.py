from __future__ import annotations

import json
import os
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any


class CallDatastore(ABC):
    @abstractmethod
    async def append_call_event(self, call_id: str, event: dict[str, Any]) -> None:
        raise NotImplementedError

    @abstractmethod
    async def load_call_events(self, call_id: str) -> list[dict[str, Any]]:
        raise NotImplementedError

    @abstractmethod
    async def save_call_projection(self, call_id: str, projection: dict[str, Any]) -> None:
        raise NotImplementedError

    @abstractmethod
    async def load_call_projection(self, call_id: str) -> dict[str, Any] | None:
        raise NotImplementedError

    @abstractmethod
    async def reserve_idempotency(self, action: str, key: str, value: dict[str, Any]) -> tuple[bool, dict[str, Any] | None]:
        raise NotImplementedError

    @abstractmethod
    async def list_call_projections(self) -> list[dict[str, Any]]:
        raise NotImplementedError


class InMemoryDatastore(CallDatastore):
    def __init__(self) -> None:
        self._events: dict[str, list[dict[str, Any]]] = {}
        self._projections: dict[str, dict[str, Any]] = {}
        self._idempotency: dict[str, dict[str, Any]] = {}

    async def append_call_event(self, call_id: str, event: dict[str, Any]) -> None:
        self._events.setdefault(call_id, []).append(event)

    async def load_call_events(self, call_id: str) -> list[dict[str, Any]]:
        return list(self._events.get(call_id, []))

    async def save_call_projection(self, call_id: str, projection: dict[str, Any]) -> None:
        self._projections[call_id] = projection

    async def load_call_projection(self, call_id: str) -> dict[str, Any] | None:
        item = self._projections.get(call_id)
        return dict(item) if item else None

    async def reserve_idempotency(self, action: str, key: str, value: dict[str, Any]) -> tuple[bool, dict[str, Any] | None]:
        compound = f"{action}:{key}"
        if compound in self._idempotency:
            return False, self._idempotency[compound]
        self._idempotency[compound] = value
        return True, None

    async def list_call_projections(self) -> list[dict[str, Any]]:
        return [dict(item) for item in self._projections.values()]


class RedisDatastore(CallDatastore):
    def __init__(self, redis_url: str) -> None:
        import redis.asyncio as redis

        self._redis = redis.from_url(redis_url, decode_responses=True)

    async def append_call_event(self, call_id: str, event: dict[str, Any]) -> None:
        await self._redis.rpush(f"call:{call_id}:events", json.dumps(event))

    async def load_call_events(self, call_id: str) -> list[dict[str, Any]]:
        raw = await self._redis.lrange(f"call:{call_id}:events", 0, -1)
        return [json.loads(item) for item in raw]

    async def save_call_projection(self, call_id: str, projection: dict[str, Any]) -> None:
        await self._redis.set(f"call:{call_id}:projection", json.dumps(projection))

    async def load_call_projection(self, call_id: str) -> dict[str, Any] | None:
        raw = await self._redis.get(f"call:{call_id}:projection")
        return json.loads(raw) if raw else None

    async def reserve_idempotency(self, action: str, key: str, value: dict[str, Any]) -> tuple[bool, dict[str, Any] | None]:
        bucket = f"idempotency:{action}:{key}"
        set_ok = await self._redis.set(bucket, json.dumps(value), nx=True, ex=86400)
        if set_ok:
            return True, None
        raw = await self._redis.get(bucket)
        return False, json.loads(raw) if raw else None

    async def list_call_projections(self) -> list[dict[str, Any]]:
        keys = await self._redis.keys("call:*:projection")
        if not keys:
            return []
        raw_items = await self._redis.mget(keys)
        return [json.loads(item) for item in raw_items if item]


class PostgresDatastore(CallDatastore):
    def __init__(self, dsn: str) -> None:
        import asyncpg

        self._dsn = dsn
        self._asyncpg = asyncpg
        self._pool = None

    async def _pool_or_init(self):
        if self._pool is None:
            self._pool = await self._asyncpg.create_pool(self._dsn)
            async with self._pool.acquire() as conn:
                await conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS call_events (
                        call_id TEXT NOT NULL,
                        event_idx BIGSERIAL PRIMARY KEY,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    CREATE TABLE IF NOT EXISTS call_projection (
                        call_id TEXT PRIMARY KEY,
                        payload JSONB NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    );
                    CREATE TABLE IF NOT EXISTS call_idempotency (
                        action TEXT NOT NULL,
                        idem_key TEXT NOT NULL,
                        payload JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (action, idem_key)
                    );
                    """
                )
        return self._pool

    async def append_call_event(self, call_id: str, event: dict[str, Any]) -> None:
        pool = await self._pool_or_init()
        async with pool.acquire() as conn:
            await conn.execute("INSERT INTO call_events(call_id, payload) VALUES($1, $2::jsonb)", call_id, json.dumps(event))

    async def load_call_events(self, call_id: str) -> list[dict[str, Any]]:
        pool = await self._pool_or_init()
        async with pool.acquire() as conn:
            rows = await conn.fetch("SELECT payload FROM call_events WHERE call_id = $1 ORDER BY event_idx", call_id)
            return [dict(row["payload"]) for row in rows]

    async def save_call_projection(self, call_id: str, projection: dict[str, Any]) -> None:
        pool = await self._pool_or_init()
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO call_projection(call_id, payload, updated_at)
                VALUES($1, $2::jsonb, NOW())
                ON CONFLICT (call_id)
                DO UPDATE SET payload = EXCLUDED.payload, updated_at = NOW()
                """,
                call_id,
                json.dumps(projection),
            )

    async def load_call_projection(self, call_id: str) -> dict[str, Any] | None:
        pool = await self._pool_or_init()
        async with pool.acquire() as conn:
            row = await conn.fetchrow("SELECT payload FROM call_projection WHERE call_id = $1", call_id)
            return dict(row["payload"]) if row else None

    async def reserve_idempotency(self, action: str, key: str, value: dict[str, Any]) -> tuple[bool, dict[str, Any] | None]:
        pool = await self._pool_or_init()
        async with pool.acquire() as conn:
            inserted = await conn.execute(
                """
                INSERT INTO call_idempotency(action, idem_key, payload)
                VALUES($1, $2, $3::jsonb)
                ON CONFLICT DO NOTHING
                """,
                action,
                key,
                json.dumps(value),
            )
            if inserted.endswith("1"):
                return True, None
            row = await conn.fetchrow(
                "SELECT payload FROM call_idempotency WHERE action = $1 AND idem_key = $2",
                action,
                key,
            )
            return False, dict(row["payload"]) if row else None

    async def list_call_projections(self) -> list[dict[str, Any]]:
        pool = await self._pool_or_init()
        async with pool.acquire() as conn:
            rows = await conn.fetch("SELECT payload FROM call_projection")
            return [dict(row["payload"]) for row in rows]


def create_datastore() -> CallDatastore:
    backend = os.getenv("CALL_DATASTORE", "memory").lower()
    if backend == "redis":
        return RedisDatastore(os.getenv("REDIS_URL", "redis://localhost:6379/0"))
    if backend == "postgres":
        return PostgresDatastore(os.getenv("POSTGRES_DSN", "postgresql://postgres:postgres@localhost:5432/mimir"))
    return InMemoryDatastore()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()
