# MIMIR Services (Python + PBX)

This directory contains the current MIMIR prototype stack:

- **SIP Flow Handler** (`services/sip-flow-handler`)
- **Media Bridge** (`services/media-bridge`)
- **Asterisk PBX** (`services/pbx`) with pre-provisioned SIP users and scientist extensions

The stack is designed for a single-click local deploy with only API keys in `.env` as required input; model selection is optional and defaults safely.

## Services

1. **SIP Flow Handler** (`services/sip-flow-handler`)
   - Owns controller-side invite policy checks, source/trunk controls, and call state machine.
   - Talks to Media Bridge exclusively over network control APIs.
   - Currently exposes HTTP endpoints that model inbound SIP events; it does not yet register to a SIP provider or terminate SIP directly.

2. **Media Bridge** (`services/media-bridge`)
   - Owns media-session abstractions, runtime selection, and AI/media bridge telemetry.
   - Enforces `direction == inbound` before allocating media sessions.
   - Exposes HTTP control APIs and an SSE event stream.
   - Currently models media lifecycle in-process; it does not yet terminate RTP or attach to a live AI audio backend.

3. **Asterisk PBX** (`services/pbx`)
   - Exposes SIP on `udp/5060`.
   - Exposes RTP media range on `udp/10000-10099`.
   - Ships with pre-configured extensions and passwords for quick softphone testing.
   - Currently owns the only real SIP edge in the stack.

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

Those scientist extensions are PBX-local SIP endpoints today. They are not yet wired to trigger the HTTP controller path in `sip-flow-handler`.

## Configuration

### 1) Create environment file

```bash
cd services
cp .env.example .env
```

Required: `OPENAI_API_KEY`.

Optional: `SCIENTIST_MODEL_NAME` to globally switch model versions for every scientist profile without editing JSON.

### 2) Start the prototype stack

```bash
cd services
docker compose up --build
```

## Service endpoints

- SIP Flow Handler control API: `http://localhost:8080`
- Media Bridge control API: `http://localhost:8081`
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

## Current gaps

The stack does not yet provide the full inbound SIP-to-AI path described in the higher-level architecture:

- No service currently registers upstream with a SIP provider on behalf of `sip-flow-handler`.
- The PBX dialplan does not yet broker inbound calls to `sip-flow-handler` and onward to `media-bridge`.
- `media-bridge` does not yet exchange live RTP/audio with Asterisk or OpenAI Realtime.
- An independent orchestrator service has not been split out yet; monitoring and control remain embedded in the handler/runtime pair.

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

- **Target architecture:** SIP Flow Handler owns SIP dialog termination.
- **Current implementation:** Asterisk owns SIP dialog termination for the local lab extensions.
- **Target architecture:** Media Bridge owns media/websocket cleanup.
- **Current implementation:** Media Bridge owns in-process session state and telemetry only.
- **Controller contract/state model arbitrates timeout and failure transitions**.

## Inbound-only policy

- Only inbound INVITE paths are supported.
- Outbound call origination APIs are intentionally omitted/rejected.
- Call logging is mandatory for abuse/fraud investigations.
