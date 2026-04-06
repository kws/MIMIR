# MIMIR Services (Python + PBX)

This directory contains the deployable MIMIR stack:

- **SIP Flow Handler** (`services/sip-flow-handler`)
- **Media Bridge** (`services/media-bridge`)
- **Asterisk PBX** (`services/pbx`) with pre-provisioned SIP users and scientist extensions

The stack is designed for a single-click local deploy with only API keys in `.env` as required input; model selection is optional and defaults safely.

## Services

1. **SIP Flow Handler** (`services/sip-flow-handler`)
   - Owns SIP registration state, inbound INVITE policy checks, source/trunk controls, and call state machine.
   - Talks to Media Bridge exclusively over network control APIs.

2. **Media Bridge** (`services/media-bridge`)
   - Owns RTP/media lifecycle abstractions, runtime selection, and AI/media bridge telemetry.
   - Enforces `direction == inbound` before allocating media sessions.
   - Exposes HTTP control APIs and an SSE event stream.

3. **Asterisk PBX** (`services/pbx`)
   - Exposes SIP on `udp/5060`.
   - Exposes RTP media range on `udp/10000-10099`.
   - Ships with pre-configured extensions and passwords for quick softphone testing.

## Pre-configured SIP accounts

Use these credentials in your SIP clients:

| Role | Extension | Password |
|---|---:|---|
| Operator (default caller) | `1000` | `lab1000` |
| Scientist - Arthur C. Clarke | `2001` | `clarke2001` |
| Scientist - Albert Einstein | `2002` | `einstein2002` |
| Scientist - Erwin Schrödinger | `2003` | `schrodinger2003` |
| Scientist - Marie Curie | `2004` | `curie2004` |
| Scientist - Niels Bohr | `2005` | `bohr2005` |
| Scientist - Nikola Tesla | `2006` | `tesla2006` |

Dial any of `2001` to `2006` from extension `1000` to reach a scientist account.

> Tip: register two SIP clients (for example `1000` and `2002`) to immediately test bi-directional audio.

## Configuration

### 1) Create environment file

```bash
cd services
cp .env.example .env
```

Required: `OPENAI_API_KEY`.

Optional: `SCIENTIST_MODEL_NAME` to globally switch model versions for every scientist profile without editing JSON.

### 2) Start the full stack

```bash
cd services
docker compose up --build
```

## Service endpoints

- SIP Flow Handler: `http://localhost:8080`
- Media Bridge: `http://localhost:8081`
- Asterisk SIP listener: `udp://localhost:5060`
- Asterisk RTP range: `udp://localhost:10000-10099`

## Scientist AI profile defaults

Default AI profile mappings are preloaded from:

- `services/config/ai-profiles.json`

This maps extensions `2001/2002/2003/2004/2005/2006` to scientist-style prompts and greetings. The `model_name` values in this file support `${ENV_VAR:-default}` expansion at load time.

## Control contracts

- HTTP control API: `contracts/media-control.openapi.yaml`
- Event stream: `GET /v1/media/events` (SSE)
- gRPC contract: `contracts/media-control.proto`

No in-process references are permitted between SIP flow logic and media runtime logic; interaction is contract-driven over APIs/events.

## Observability baseline

- SIP Flow Handler and Media Bridge expose `GET /metrics` for Prometheus scraping.
- Structured logs include `call_id` and `bridge_session_id` where available.
- Media Bridge telemetry endpoint (`POST /v1/media/sessions/{session_id}/telemetry`) captures RTP loss/jitter and websocket reconnect/error signals.
- Dashboards and SLO recording/alert rules are provided in `/observability`.

## Runtime selection and stability guardrails

- `MEDIA_BACKEND_SECONDARY_PERCENT` controls percentage routed to secondary backend (0-100).
- Backend routing is deterministic by `call_id` hash.
- Runtime can be pinned per call via `metadata.bridge_runtime`.
- Session states remain stable (`created`, `active`, `terminated`) independent of backend.

## Ownership boundaries

- **SIP Flow Handler owns SIP dialog termination**.
- **Media Bridge owns media/websocket cleanup**.
- **Controller contract/state model arbitrates timeout and failure transitions**.

## Inbound-only policy

- Only inbound INVITE paths are supported.
- Outbound call origination APIs are intentionally omitted/rejected.
- Call logging is mandatory for abuse/fraud investigations.
