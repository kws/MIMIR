# MIMIR 🧙‍♂️

Python-first, inbound-only SIP-to-AI voice bridge prototype.

MIMIR currently ships a three-service local lab stack:

- **SIP Flow Handler** (`services/sip-flow-handler`) exposes HTTP control endpoints for invite policy, call state, and orchestration logic.
- **Media Bridge** (`services/media-bridge`) exposes HTTP control endpoints for media-session lifecycle, runtime routing, and telemetry.
- **Asterisk PBX** (`services/pbx`) provides local SIP registrations, extension dialing, and RTP ports for sandbox testing.

## Project layout

- `services/sip-flow-handler` — FastAPI service for SIP-side policy + call orchestration.
- `services/media-bridge` — FastAPI service for media control/session runtime.
- `services/pbx` — Containerized Asterisk PBX with pre-configured users/extensions.
- `services/config/ai-profiles.json` — default scientist AI profile mappings for extensions.
- `contracts/` — OpenAPI + protobuf controller contracts.
- `observability/` — Prometheus/Grafana artifacts.

## Requirements

- Docker + Docker Compose
- OpenAI API key

## Quickstart (local control-plane sandbox)

```bash
cd services
cp .env.example .env
# required: OPENAI_API_KEY
# optional: SCIENTIST_MODEL_NAME to test a different model version
docker compose up --build
```

## SIP accounts and extensions

The PBX boots with these local accounts:

| Role | Extension | Password |
|---|---:|---|
| Operator | `1000` | `lab1000` |
| Scientist - Arthur C. Clarke | `2001` | `clarke2001` |
| Scientist - Albert Einstein | `2002` | `einstein2002` |
| Scientist - Erwin Schrödinger | `2003` | `schrodinger2003` |
| Scientist - Marie Curie | `2004` | `curie2004` |
| Scientist - Niels Bohr | `2005` | `bohr2005` |
| Scientist - Nikola Tesla | `2006` | `tesla2006` |

Register your SIP client to `localhost:5060/udp` and dial one of the scientist extensions.

At the moment, those scientist extensions are still PBX-local SIP endpoints. Dialing them exercises the PBX dialplan, but it does not yet broker the call through `sip-flow-handler` into a live AI media runtime.

## Service endpoints

- SIP Flow Handler control API: `http://localhost:8080`
- Media Bridge control API: `http://localhost:8081`
- Asterisk SIP listener: `udp://localhost:5060`
- Asterisk RTP: `udp://localhost:10000-10099`

## Current Status

The repository currently implements the control plane more completely than the live telephony path:

- `sip-flow-handler` does not yet register to an upstream SIP provider or listen for SIP directly.
- The PBX dialplan does not yet hand inbound extension calls to `sip-flow-handler`.
- `media-bridge` tracks sessions and emits lifecycle events, but it does not yet terminate RTP or connect a live AI audio backend.
- A separate orchestrator service is not present yet; orchestration logic currently lives inside `sip-flow-handler`.

In other words, the stack is a useful prototype for controller state transitions and observability, but not yet an end-to-end inbound SIP-to-AI bridge.

## Inbound-only policy

Outbound origination is explicitly rejected. The SIP handler and media bridge both enforce inbound-only behavior and emit auditable reasons for rejections/failures.

## Observability

Both services expose `GET /metrics` for Prometheus scraping.

See:

- `observability/README.md`
- `observability/prometheus/slo-rules.yaml`
- `observability/grafana/call-runtime-evidence-dashboard.json`

## Contracts

- OpenAPI: `contracts/media-control.openapi.yaml`
- Protobuf: `contracts/media-control.proto`

## License

MIT (see `LICENSE`).
