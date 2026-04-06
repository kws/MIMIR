# MIMIR Services

This directory contains the current Python prototype stack:

- **Orchestrator** (`services/orchestrator`)
- **Media Bridge** (`services/media-bridge`)
- **PBX fixture** (`services/pbx`)

The stack is intentionally provider-agnostic. Telephony edges such as Asterisk, Twilio, or a generic SIP provider are expected to live behind adapter-specific integrations instead of being baked into the core services. The PBX is included in the one-click compose flow as a local test fixture.

## Services

1. **Orchestrator** (`services/orchestrator`)
   - Owns normalized inbound-call intake, policy checks, adapter allowlists, call state, and persona selection.
   - Talks to Media Bridge over HTTP and consumes media lifecycle events over SSE.
   - Does not terminate SIP, register with providers, or pretend to be a SIP stack.

2. **Media Bridge** (`services/media-bridge`)
   - Owns media session lifecycle, runtime routing, and telemetry ingestion.
   - Exposes a session-oriented API and a single media event stream.
   - Does not subscribe back into orchestrator state or own telephony-provider logic.

## Configuration

### 1) Create environment file

```bash
cd services
cp .env.example .env
```

Required: `OPENAI_API_KEY`.

Optional: `SCIENTIST_MODEL_NAME` to switch the default persona model without editing JSON.

### 2) Start the current stack

```bash
cd services
docker compose up --build
```

## Service endpoints

- PBX fixture: `udp://localhost:5060` and `udp://localhost:10000-10099`
- Orchestrator: `http://localhost:8080`
- Media Bridge: `http://localhost:8081`

## Current orchestrator API

- `POST /v1/calls/inbound` — accept a normalized inbound call from an edge adapter
- `POST /v1/calls/{call_id}/hangup` — end a call
- `GET /v1/calls/{call_id}` — inspect current call projection
- `GET /v1/call-events` — stream orchestrator lifecycle events
- `PUT /v1/config/ai-profiles` — update profile mappings

## Current media bridge API

- `POST /v1/media/sessions`
- `POST /v1/media/sessions/{session_id}/start`
- `POST /v1/media/sessions/{session_id}/stop`
- `GET /v1/media/sessions/{session_id}`
- `POST /v1/media/sessions/{session_id}/telemetry`
- `GET /v1/media/events`

## AI profile defaults

Default profile mappings are preloaded from:

- `services/config/ai-profiles.json`

This keeps persona configuration in the orchestrator layer instead of tying it to any specific telephony provider.

## Contracts

- HTTP control API: `contracts/media-control.openapi.yaml`
- gRPC contract: `contracts/media-control.proto`

## Intentional omissions

The current Python stack does not yet include:

- a SIP registration stack
- an Asterisk adapter
- a Twilio adapter
- live RTP or OpenAI Realtime media plumbing
- NAT traversal logic at the edge layer

Those concerns are expected to arrive as adapter and media-runtime work, not as hidden behavior inside the orchestrator.

## PBX fixture

`services/pbx` is included in the default compose stack to support simple local and continuous testing.

Important:

- It is a fixture, not the architecture center.
- It is not yet wired into the orchestrator as a real provider adapter.
- Hosted-provider support should still be designed through the same normalized adapter boundary.
