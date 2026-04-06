# MIMIR

Provider-agnostic inbound voice orchestration prototype.

MIMIR is being shaped around three separate concerns:

- **Orchestrator** (`services/orchestrator`) owns call state, policy, persona selection, and audit-friendly lifecycle events.
- **Media Bridge** (`services/media-bridge`) owns media session lifecycle, runtime selection, and media telemetry.
- **Edge adapters** will normalize self-hosted PBXs and hosted providers such as Twilio into the orchestrator's internal call-control model.

The default Python stack currently ships the first two pieces plus a one-click PBX fixture for local and continuous testing. That PBX is present as a test harness, not as the architecture center.

## Project layout

- `services/orchestrator` — FastAPI orchestrator for normalized inbound-call control.
- `services/media-bridge` — FastAPI media session service.
- `services/config/ai-profiles.json` — default persona/profile mappings keyed by extension.
- `services/pbx` — Asterisk-based PBX fixture used for local and continuous testing.
- `contracts/` — published service contracts for the media bridge.
- `observability/` — Prometheus/Grafana artifacts.

## Requirements

- Docker + Docker Compose
- OpenAI API key

## Quickstart

```bash
cd services
cp .env.example .env
docker compose up --build
```

This starts:

- PBX fixture: `udp://localhost:5060` and `udp://localhost:10000-10099`
- Orchestrator: `http://localhost:8080`
- Media Bridge: `http://localhost:8081`

## Current status

The repository is now explicitly organized around a provider-agnostic boundary:

- The orchestrator accepts normalized inbound-call requests from future edge adapters at `POST /v1/calls/inbound`.
- The media bridge exposes session creation, activation, termination, telemetry, and SSE media lifecycle events.
- A PBX fixture is included in the default stack for simple local testing, but it is not yet wired into the orchestrator as a real adapter.
- The current media bridge still models session state and telemetry in-process; live RTP and provider-specific media handling remain future work.

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
