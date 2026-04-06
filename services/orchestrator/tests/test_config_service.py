import json

from app.config_service import AIProfile, AIProfileConfigService


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
                        "vad_mode": "server_vad",
                        "model_name": "gpt-realtime-mini",
                    },
                    "2001|*": {
                        "voice": "verse",
                        "instructions": "extension",
                        "greeting": "hey",
                        "vad_mode": "server_vad",
                        "model_name": "gpt-4o-realtime",
                    },
                    "2001|alice": {
                        "voice": "sage",
                        "instructions": "pair",
                        "greeting": "hi alice",
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
            vad_mode="manual",
            model_name="gpt-realtime-mini",
        ),
    )

    reloaded = AIProfileConfigService(str(config_path))
    resolved = reloaded.resolve("2002", "bob")

    assert resolved.voice == "echo"
    assert resolved.greeting == "hello bob"
