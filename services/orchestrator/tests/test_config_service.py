import json

from mimir.orchestrator.config_service import AIProfile, AIProfileConfigService


def test_resolve_prefers_pair_over_extension_and_default(tmp_path) -> None:
    config_path = tmp_path / "profiles.json"
    config_path.write_text(
        json.dumps(
            {
                "profiles": {
                    "*|*": {
                        "voice": "alloy",
                        "instructions": "default",
                        "greeting": "hello",
                        "initialisation": "Ring, ring. Say hello.",
                        "vad_mode": "server_vad",
                        "model_name": "gpt-realtime-mini",
                    },
                    "2001|*": {
                        "voice": "verse",
                        "instructions": "extension",
                        "greeting": "hey",
                        "initialisation": "Ring, ring. Say hey.",
                        "vad_mode": "server_vad",
                        "model_name": "gpt-4o-realtime",
                    },
                    "2001|alice": {
                        "voice": "sage",
                        "instructions": "pair",
                        "greeting": "hi alice",
                        "initialisation": "Ring, ring. Say hi to Alice.",
                        "vad_mode": "manual",
                        "model_name": "gpt-4o-realtime",
                    },
                }
            }
        )
    )

    service = AIProfileConfigService(str(config_path))

    resolved = service.resolve("2001", "alice")

    assert resolved.voice == "sage"
    assert resolved.instructions == "pair"


def test_upsert_saves_pair_mapping(tmp_path) -> None:
    config_path = tmp_path / "profiles.json"
    service = AIProfileConfigService(str(config_path))

    service.upsert(
        "2002",
        "bob",
        AIProfile(
            voice="echo",
            instructions="for bob",
            greeting="hello bob",
            initialisation="Ring, ring. Say hello bob.",
            vad_mode="manual",
            model_name="gpt-realtime-mini",
        ),
    )

    reloaded = AIProfileConfigService(str(config_path))
    resolved = reloaded.resolve("2002", "bob")

    assert resolved.voice == "echo"
    assert resolved.greeting == "hello bob"
    assert resolved.initialisation == "Ring, ring. Say hello bob."


def test_resolve_loads_persona_from_relative_path(tmp_path, monkeypatch) -> None:
    config_dir = tmp_path / "config"
    persona_dir = config_dir / "personas"
    persona_dir.mkdir(parents=True)
    (persona_dir / "clarke.persona.md").write_text(
        "\n".join(
            [
                "name: Arthur C. Clarke",
                "voice: verse",
                "greeting: Hello there! Arthur Clarke speaking.",
                "initialisation: Ring, ring. The phone is ringing. You pick it up and say: 'Hello there! Arthur Clarke speaking.'",
                "vad_mode: server_vad",
                "model_name: ${SCIENTIST_MODEL_NAME:-gpt-realtime-mini}",
                "---",
                "You are Arthur C. Clarke.",
                "Remain entirely in character.",
            ]
        ),
        encoding="utf-8",
    )
    config_path = config_dir / "profiles.json"
    config_path.write_text(json.dumps({"profiles": {"2001|*": {"persona_path": "personas/clarke.persona.md"}}}), encoding="utf-8")
    monkeypatch.setenv("SCIENTIST_MODEL_NAME", "gpt-4o-realtime-preview")

    service = AIProfileConfigService(str(config_path))

    resolved = service.resolve("2001", "alice")

    assert resolved.voice == "verse"
    assert resolved.greeting == "Hello there! Arthur Clarke speaking."
    assert resolved.initialisation == "Ring, ring. The phone is ringing. You pick it up and say: 'Hello there! Arthur Clarke speaking.'"
    assert resolved.model_name == "gpt-4o-realtime-preview"
    assert "Arthur C. Clarke" in resolved.instructions


def test_upsert_preserves_persona_references_for_existing_profiles(tmp_path) -> None:
    config_dir = tmp_path / "config"
    persona_dir = config_dir / "personas"
    persona_dir.mkdir(parents=True)
    (persona_dir / "default.persona.md").write_text(
        "\n".join(
            [
                "voice: alloy",
                "greeting: Hello from the lab.",
                "initialisation: Ring, ring. Say hello from the lab.",
                "vad_mode: server_vad",
                "model_name: ${SCIENTIST_MODEL_NAME:-gpt-realtime-mini}",
                "---",
                "You are a helpful historical scientist persona.",
            ]
        ),
        encoding="utf-8",
    )
    config_path = config_dir / "profiles.json"
    config_path.write_text(json.dumps({"profiles": {"*|*": {"persona_path": "personas/default.persona.md"}}}), encoding="utf-8")

    service = AIProfileConfigService(str(config_path))
    service.upsert(
        "2002",
        "bob",
        AIProfile(
            voice="echo",
            instructions="for bob",
            greeting="hello bob",
            vad_mode="manual",
            model_name="gpt-realtime-mini",
        ),
    )

    saved = json.loads(config_path.read_text(encoding="utf-8"))

    assert saved["profiles"]["*|*"] == {"persona_path": "personas/default.persona.md"}
    assert saved["profiles"]["2002|bob"]["voice"] == "echo"
    assert "initialisation" not in saved["profiles"]["2002|bob"]
