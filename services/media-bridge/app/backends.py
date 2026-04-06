from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Protocol

from .controller_contract import BridgeSessionStatus, CreateMediaSessionRequest


@dataclass
class BackendSession:
    session_id: str
    call_id: str
    status: str = BridgeSessionStatus.CREATED.value
    reason: str | None = None
    runtime: str = "python"
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    first_audio_at: float | None = None


class MediaBackend(Protocol):
    runtime_name: str

    def create(self, request: CreateMediaSessionRequest, session_id: str) -> BackendSession:
        ...

    def start(self, session: BackendSession) -> BackendSession:
        ...

    def stop(self, session: BackendSession, reason: str) -> BackendSession:
        ...


class PythonMediaBackend:
    """Default Python media backend."""

    runtime_name = "python"

    def create(self, request: CreateMediaSessionRequest, session_id: str) -> BackendSession:
        return BackendSession(session_id=session_id, call_id=request.call_id, runtime=self.runtime_name)

    def start(self, session: BackendSession) -> BackendSession:
        if session.status != BridgeSessionStatus.TERMINATED.value:
            session.status = BridgeSessionStatus.ACTIVE.value
        return session

    def stop(self, session: BackendSession, reason: str) -> BackendSession:
        session.status = BridgeSessionStatus.TERMINATED.value
        session.reason = reason
        return session


class PythonOptimizedBackend:
    """Optional secondary backend placeholder behind the same Python contract."""

    runtime_name = "python-optimized"

    def create(self, request: CreateMediaSessionRequest, session_id: str) -> BackendSession:
        return BackendSession(session_id=session_id, call_id=request.call_id, runtime=self.runtime_name)

    def start(self, session: BackendSession) -> BackendSession:
        if session.status != BridgeSessionStatus.TERMINATED.value:
            session.status = BridgeSessionStatus.ACTIVE.value
        return session

    def stop(self, session: BackendSession, reason: str) -> BackendSession:
        session.status = BridgeSessionStatus.TERMINATED.value
        session.reason = reason
        return session


class BackendRouter:
    """Deterministic, call-id-based runtime assignment."""

    def __init__(self) -> None:
        self.primary = PythonMediaBackend()
        self.secondary = PythonOptimizedBackend()
        self.secondary_percentage = max(0, min(100, int(os.getenv("MEDIA_BACKEND_SECONDARY_PERCENT", "0"))))

    def choose_backend(self, call_id: str, requested_runtime: str | None = None) -> MediaBackend:
        if requested_runtime == self.secondary.runtime_name:
            return self.secondary
        if requested_runtime == self.primary.runtime_name:
            return self.primary

        bucket = int(hashlib.sha256(call_id.encode("utf-8")).hexdigest(), 16) % 100
        if bucket < self.secondary_percentage:
            return self.secondary
        return self.primary
