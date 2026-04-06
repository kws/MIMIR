from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pydantic import BaseModel


class AIProfile(BaseModel):
    voice: str
    instructions: str
    greeting: str
    vad_mode: str
    model_name: str


class AIProfileConfigService:
    def __init__(self, config_path: str) -> None:
        self._path = Path(config_path)
        self._profiles: dict[str, AIProfile] = {}
        self._load()

    def _load(self) -> None:
        if not self._path.exists():
            self._profiles = {}
            return
        payload = json.loads(self._path.read_text(encoding="utf-8"))
        raw_profiles = payload.get("profiles", {})
        self._profiles = {key: AIProfile(**value) for key, value in raw_profiles.items()}

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"profiles": {key: value.model_dump() for key, value in self._profiles.items()}}
        self._path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")

    @staticmethod
    def _pair_key(called_extension: str, callee: str) -> str:
        return f"{called_extension}|{callee}"

    @staticmethod
    def _extension_key(called_extension: str) -> str:
        return f"{called_extension}|*"

    @staticmethod
    def _default_key() -> str:
        return "*|*"

    def resolve(self, called_extension: str, callee: str) -> AIProfile:
        for key in (
            self._pair_key(called_extension, callee),
            self._extension_key(called_extension),
            self._default_key(),
        ):
            profile = self._profiles.get(key)
            if profile:
                return profile
        return AIProfile(
            model_name="gpt-4o-realtime-preview-2024-12-17",
            voice="alloy",
            instructions="Speak like the configured historical scientist.",
            greeting="Hello, this is your scientist speaking.",
            vad_mode="server_vad",
        )

    def upsert(self, called_extension: str, callee: str, profile: AIProfile) -> None:
        self._profiles[self._pair_key(called_extension, callee)] = profile
        self._save()

