# MIMIR Services (Python-only)

This directory contains the deployable Python implementation of MIMIR.

## Services

1. **SIP Flow Handler** (`services/sip-flow-handler`)
   - Owns SIP registration state, inbound INVITE policy checks, source/trunk controls, and call state machine.
   - Talks to Media Bridge exclusively over network control APIs.

2. **Media Bridge** (`services/media-bridge`)
   - Owns RTP/media lifecycle abstractions, runtime selection, and AI/media bridge telemetry.
   - Enforces `direction == inbound` before allocating media sessions.
   - Exposes HTTP control APIs and an SSE event stream.

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

## Run locally

```bash
# terminal 1
cd services/media-bridge
uvicorn app.main:app --host 0.0.0.0 --port 8081

# terminal 2
cd services/sip-flow-handler
MEDIA_BRIDGE_URL=http://localhost:8081 uvicorn app.main:app --host 0.0.0.0 --port 8080
```

Useful controls:

- `ALLOWED_TRUNK_SOURCES` (comma-separated allowlist, based on `X-Trunk-Source`/`X-Source` SIP headers)
- `MAX_ACTIVE_CALLS_PER_SOURCE` (active-call rate limit per source/trunk, default `20`)

## Build containers

```bash
docker build -t mimir-media-bridge ./services/media-bridge
docker build -t mimir-sip-flow-handler ./services/sip-flow-handler
```
