from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

from pydantic import BaseModel


class AIProfile(BaseModel):
    voice: str
    instructions: str
    greeting: str
    initialisation: str | None = None
    vad_mode: str
    model_name: str


class AIProfileConfigService:
    def __init__(self, config_path: str) -> None:
        self._path = Path(config_path)
        self._profiles: dict[str, AIProfile] = {}
        self._profile_sources: dict[str, dict[str, Any]] = {}
        self._load()

    def _load(self) -> None:
        if not self._path.exists():
            self._profiles = {}
            self._profile_sources = {}
            return
        payload = json.loads(self._path.read_text(encoding="utf-8"))
        raw_profiles = payload.get("profiles", {})
        self._profile_sources = {key: dict(value) for key, value in raw_profiles.items()}
        self._profiles = {key: AIProfile(**self._resolve_profile_variables(value)) for key, value in raw_profiles.items()}

    @staticmethod
    def _resolve_variable(value: Any) -> Any:
        if not isinstance(value, str):
            return value

        pattern = re.fullmatch(r"\$\{([A-Z0-9_]+)(?::-(.*))?\}", value)
        if not pattern:
            return value

        env_name, default = pattern.group(1), pattern.group(2)
        return os.getenv(env_name, default or "")

    def _resolve_profile_variables(self, profile_payload: dict[str, Any]) -> dict[str, Any]:
        resolved = dict(profile_payload)
        persona_path = resolved.pop("persona_path", None)
        if persona_path is not None:
            resolved = {**self._load_persona(self._resolve_variable(persona_path)), **resolved}
        for key, value in resolved.items():
            resolved[key] = self._resolve_variable(value)
        return resolved

    def _load_persona(self, persona_path: str) -> dict[str, Any]:
        path = Path(persona_path)
        if not path.is_absolute():
            path = self._path.parent / path
        persona_text = path.read_text(encoding="utf-8")

        metadata: dict[str, Any] = {}
        lines = persona_text.splitlines()
        separator_index: int | None = None
        for index, line in enumerate(lines):
            stripped = line.strip()
            if stripped == "---":
                separator_index = index
                break
            if not stripped:
                continue
            key, separator, raw_value = line.partition(":")
            if not separator:
                raise ValueError(f"invalid persona header line in {path}: {line!r}")
            metadata[key.strip()] = self._strip_quotes(raw_value.strip())

        if separator_index is None:
            raise ValueError(f"persona file {path} is missing the '---' instructions separator")

        instructions = "\n".join(lines[separator_index + 1 :]).strip()
        if not instructions:
            raise ValueError(f"persona file {path} does not define any instructions")
        metadata["instructions"] = instructions
        return metadata

    @staticmethod
    def _strip_quotes(value: str) -> str:
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            return value[1:-1]
        return value

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"profiles": self._profile_sources}
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

    @staticmethod
    def _default_model_name() -> str:
        return os.getenv("SCIENTIST_MODEL_NAME", "gpt-realtime-mini")

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
            model_name=self._default_model_name(),
            voice="alloy",
            instructions="Speak like the configured historical scientist.",
            greeting="Hello, this is your scientist speaking.",
            initialisation=None,
            vad_mode="server_vad",
        )

    def upsert(self, called_extension: str, callee: str, profile: AIProfile) -> None:
        key = self._pair_key(called_extension, callee)
        self._profiles[key] = profile
        self._profile_sources[key] = profile.model_dump(exclude_none=True)
        self._save()
