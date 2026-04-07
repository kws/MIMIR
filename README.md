# MIMIR

Provider-agnostic inbound voice orchestration prototype.

MIMIR is being shaped around three separate concerns:

- **Orchestrator** (`services/orchestrator`) owns call state, policy, persona selection, and audit-friendly lifecycle events.
- **Media Bridge** (`services/media-bridge`) owns media session lifecycle, runtime selection, and media telemetry.
- **Edge adapters** will normalize self-hosted PBXs and hosted providers such as Twilio into the orchestrator's internal call-control model.

The default Python stack currently ships the first two pieces plus a one-click PBX fixture for local and continuous testing. That PBX is present as a test harness, not as the architecture center.

## Project layout

- `pyproject.toml` — root `uv` workspace and shared dev-tool configuration.
- `services/orchestrator` — FastAPI orchestrator for normalized inbound-call control.
- `services/media-bridge` — FastAPI media session service.
- `services/config/ai-profiles.json` — default persona/profile mappings keyed by extension.
- `services/config/personas/` — rich persona scripts referenced by the profile map.
- `services/pbx` — Asterisk-based PBX fixture used for local and continuous testing.
- `contracts/` — published service contracts for the media bridge.
- `observability/` — Prometheus/Grafana artifacts.

## Tooling (uv workspace)

MIMIR uses a Python monorepo layout with `uv` workspace coordination and `hatchling` package metadata:

- Root workspace: `pyproject.toml`
- Service projects:
  - `services/orchestrator/pyproject.toml`
  - `services/media-bridge/pyproject.toml`

### Install dependencies

```bash
uv sync --all-packages --dev
```

### Format and lint

```bash
uv run --all-packages ruff format .
uv run --all-packages ruff check .
```

### Unit tests with coverage

```bash
uv run --package mimir-orchestrator pytest
uv run --package mimir-media-bridge pytest
```

Both service projects are configured to run `pytest` with `pytest-cov` via their `pyproject.toml` settings.

## Python package namespaces

Both Python services now publish code under a shared top-level `mimir` namespace package so imports are unambiguous when both projects are open in one workspace:

- Orchestrator package path: `mimir.orchestrator` (`services/orchestrator/mimir/orchestrator`)
- Media bridge package path: `mimir.mediabridge` (`services/media-bridge/mimir/mediabridge`)

Container startup uses these module paths:

- Orchestrator: `uvicorn mimir.orchestrator.main:app`
- Media bridge: `uvicorn mimir.mediabridge.main:app`

## Requirements

- Docker + Docker Compose
- `uv` for local Python workflows
- OpenAI API key for the default `gpt-realtime-mini` path
- Optional Gemini API key for Gemini Live fixture verification

## Quickstart

```bash
cd services
cp .env.example .env
docker compose up --build
```

This starts:

- PBX fixture: `udp://localhost:5060`, `http://localhost:8088`, and `udp://localhost:10000-10099`
- Orchestrator: `http://localhost:8080`
- Media Bridge: `http://localhost:8081` and `udp://localhost:12000-12099`

## Current status

The repository is now explicitly organized around a provider-agnostic boundary:

- The orchestrator accepts normalized inbound-call requests from future edge adapters at `POST /v1/calls/inbound`.
- The media bridge now exposes session creation, live RTP activation, termination, telemetry, and SSE media lifecycle events.
- The media bridge can now verify live model runtimes with prerecorded WAV fixtures against OpenAI Realtime and Gemini Live.
- The media bridge now supports live bidirectional RTP bridging for `g711_ulaw` against OpenAI Realtime, with bridge RTP allocation returned in media-session responses.
- A PBX fixture is included in the default stack for simple local testing, but it is not yet wired into the orchestrator as a real adapter.
- SIP signaling, SDP negotiation, and provider-specific edge adapters still remain future work.

## RTP Verification

The compose stack now exposes an Asterisk `ExternalMedia` verifier path over ARI:

```bash
uv run --with httpx --with websockets python services/pbx/scripts/verify_rtp_bridge.py
```

Then place a call to extension `3001`-`3006` on the PBX fixture. Those verifier extensions map to scientist profiles `2001`-`2006` and route the call into the ARI verifier, which:

- allocates a live RTP session from the media bridge
- creates an Asterisk `ExternalMedia` channel using standard RTP/UDP
- late-binds the Asterisk RTP endpoint back into `POST /v1/media/sessions/{session_id}/start`

## Contracts

- OpenAPI: `contracts/media-control.openapi.yaml`
- Protobuf: `contracts/media-control.proto`

## Observability

Both running services expose `GET /metrics`.

See:

- `observability/README.md`
- `observability/prometheus/slo-rules.yaml`
- `observability/grafana/call-runtime-evidence-dashboard.json`

## License

MIT (see `LICENSE`).
