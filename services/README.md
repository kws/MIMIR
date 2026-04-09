# MIMIR Services

This directory contains the current Python prototype stack:

- **Orchestrator** (`services/orchestrator`)
- **Media Bridge** (`services/media-bridge`)
- **PBX fixture** (`services/pbx`)

The stack is intentionally provider-agnostic. Telephony edges such as Asterisk, Twilio, or a generic SIP provider are expected to live behind adapter-specific integrations instead of being baked into the core services. The PBX is included in the one-click compose flow as a local test fixture.

## Monorepo tooling

The repository root uses a `uv` workspace (`/pyproject.toml`) to coordinate Python tooling across services.

Each Python service has its own `pyproject.toml` and uses `hatchling` as the build backend:

- `services/orchestrator/pyproject.toml`
- `services/media-bridge/pyproject.toml`

From the repository root:

```bash
uv sync --all-packages --dev
uv run --all-packages ruff format .
uv run --all-packages ruff check .
uv run --package mimir-orchestrator pytest
uv run --package mimir-media-bridge pytest
```

Both services are configured to run unit tests with coverage output (`pytest-cov`) by default.

## Python import paths

To avoid ambiguous `app.*` imports across services, the repo now uses a shared `mimir` namespace package with service-specific modules:

- Orchestrator modules live under `mimir.orchestrator` (`services/orchestrator/mimir/orchestrator`)
- Media bridge modules live under `mimir.mediabridge` (`services/media-bridge/mimir/mediabridge`)

When starting with Uvicorn, use:

- `mimir.orchestrator.main:app`
- `mimir.mediabridge.main:app`

## Services

1. **Orchestrator** (`services/orchestrator`)
   - Owns normalized inbound-call intake, policy checks, adapter allowlists, call state, durable conversation state, and persona selection.
   - Talks to Media Bridge over HTTP and consumes media lifecycle events over SSE.
   - Does not terminate SIP, register with providers, or pretend to be a SIP stack.

2. **Media Bridge** (`services/media-bridge`)
   - Owns media session lifecycle, runtime routing, telemetry ingestion, and normalization of runtime-native conversation events.
   - Exposes a session-oriented API, light steering commands, and a single media/conversation event stream.
   - Does not subscribe back into orchestrator state or own telephony-provider logic.

## Configuration

### 1) Create environment file

```bash
cd services
cp .env.example .env
```

Required: `OPENAI_API_KEY`.

Optional:

- `GEMINI_API_KEY` for Gemini Live fixture verification
- `SCIENTIST_MODEL_NAME` to switch the default persona model without editing JSON
- `MEDIA_BRIDGE_RTP_BIND_ADDRESS`, `MEDIA_BRIDGE_RTP_ADVERTISED_ADDRESS`, `MEDIA_BRIDGE_RTP_PORT_START`, and `MEDIA_BRIDGE_RTP_PORT_END` to control live RTP binding and the advertised bridge endpoint

The default OpenAI model is `gpt-realtime-mini`.

### 2) Start the current stack

```bash
cd services
docker compose up --build
```

## Service endpoints

- PBX fixture: `udp://localhost:5060`, `http://localhost:8088`, and `udp://localhost:10000-10099`
- ARI adapter: compose service `ari-adapter`
- Orchestrator: `http://localhost:8080`
- Media Bridge: `http://localhost:8081` and `udp://localhost:12000-12099`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

## Current orchestrator API

- `POST /v1/calls/inbound` — accept a normalized inbound call from an edge adapter
- `POST /v1/calls/{call_id}/media/attach` — attach late-bound adapter media through the orchestrator
- `POST /v1/calls/{call_id}/conversation/commands` — steer an active conversation through the media bridge
- `POST /v1/calls/{call_id}/hangup` — end a call
- `GET /v1/calls/{call_id}` — inspect current call projection
- `GET /v1/call-events` — stream orchestrator lifecycle events
- `PUT /v1/config/ai-profiles` — update profile mappings

## Current media bridge API

- `POST /v1/media/sessions`
- `POST /v1/media/sessions/{session_id}/start`
- `POST /v1/media/sessions/{session_id}/fixtures/run`
- `POST /v1/media/sessions/{session_id}/conversation/commands`
- `POST /v1/media/sessions/{session_id}/stop`
- `GET /v1/media/sessions/{session_id}`
- `POST /v1/media/sessions/{session_id}/telemetry`
- `GET /v1/media/events`

Behavior notes:

- `POST /v1/media/sessions` now returns the resolved bridge RTP endpoint in `MediaSession.rtp`.
- `POST /v1/media/sessions/{session_id}/start` accepts an optional `remote_rtp` override for late-bound RTP peers such as the Asterisk `ExternalMedia` verifier.
- `POST /v1/calls/inbound` accepts `media_start_mode: "deferred"` so edge adapters can ask the orchestrator to create the media session first, then attach RTP later through `POST /v1/calls/{call_id}/media/attach`.
- `GET /v1/media/events` now carries additive `conversation.*` events. Transcript deltas are marked with top-level `transient: true`; completed turns, interruptions, and command lifecycle events are durable.
- `GET /v1/call-events` forwards both durable and transient conversation events, but only durable events are written to the orchestrator datastore.
- `GET /v1/calls/{call_id}` now includes a compact `conversation` block with status, latest completed turns, current instruction override text, and turn index.
- Live RTP support is currently limited to bidirectional `g711_ulaw` over UDP with the OpenAI Realtime runtime.

## Call orchestration vs conversation orchestration

MIMIR now treats these as related but separate layers:

- **Call orchestration** covers inbound-call acceptance, policy, persona selection, media attach, hangup, and quality/failure telemetry.
- **Conversation orchestration** covers normalized turn events, transcript deltas, interruption handling, instruction overrides, and light steering commands.

The media bridge is the runtime-facing edge that normalizes raw vendor events. The orchestrator is the durable owner of conversation state and the place where cross-call steering policy belongs.

## AI profile defaults

Default profile mappings are preloaded from:

- `services/config/ai-profiles.json`
- `services/config/personas/`

This keeps persona configuration in the orchestrator layer instead of tying it to any specific telephony provider.

Profile entries in `ai-profiles.json` may point at rich persona assets with `persona_path`. Paths are resolved relative to the config file, so the compose stack mounts the whole `services/config/` directory into the orchestrator container.
Each persona file uses simple `key: value` front matter, followed by a `---` separator and the full instruction body. The front matter can carry both a literal `greeting` and a legacy-style `initialisation` prompt for the model's forced phone-answer turn.

## Contracts

- HTTP control API: `contracts/media-control.openapi.yaml`
- gRPC contract: `contracts/media-control.proto`

## Intentional omissions

The current Python stack does not yet include:

- a SIP registration stack
- a Twilio adapter
- NAT traversal logic at the edge layer

The bridge now supports live RTP call bridging for OpenAI Realtime and still supports fixture-based runtime verification against OpenAI Realtime and Gemini Live. The PBX fixture has a local ARI adapter path, but generic SIP/session negotiation concerns are still expected to arrive as adapter and RTP/media-edge work, not as hidden behavior inside the orchestrator.

## PBX fixture

`services/pbx` is included in the default compose stack to support simple local and continuous testing.

Important:

- It is a fixture, not the architecture center.
- It includes an ARI adapter path into the orchestrator for local call-control testing.
- Hosted-provider support should still be designed through the same normalized adapter boundary.

### ARI orchestrator adapter

Compose starts the ARI adapter as the `ari-adapter` service. To run it manually from the repository root instead, use:

```bash
uv run --with httpx --with websockets python services/pbx/scripts/ari_orchestrator_adapter.py
```

With the adapter running, place a call to `3001`-`3006` on the PBX fixture. The dialplan sends the call into ARI `Stasis`, the adapter posts a normalized inbound call to the orchestrator with deferred media start, creates an Asterisk `ExternalMedia` channel, and then late-binds the resulting Asterisk RTP endpoint through `POST /v1/calls/{call_id}/media/attach`. If `ALLOWED_ADAPTERS` is set, include `asterisk-ari`.

### ExternalMedia verifier

Run the verifier from the repository root:

```bash
uv run --with httpx --with websockets python services/pbx/scripts/verify_rtp_bridge.py
```

Then place a call to `3201`-`3206` on the PBX fixture. The dialplan sends the call into ARI `Stasis`, creates an `ExternalMedia` RTP channel, and late-binds the resulting Asterisk RTP endpoint into the media bridge.

## Observability test bed

The default compose stack now runs as a proper local test bed instead of just a loose pile of services:

- `prometheus` scrapes orchestrator and media-bridge metrics
- `grafana` provisions the MIMIR call-quality dashboard automatically
- `event-trace` records `GET /v1/call-events` and `GET /v1/media/events` into `observability/artifacts/event-trace/`
- media-bridge fixture artifacts are persisted to `services/media-bridge/artifacts/`

This is enough to trace transport and runtime quality across live calls:

- inbound-to-active latency
- first-audio latency
- RTP packet loss, jitter, sender lag, and jitter-buffer depth
- duplicate, late, missing, and out-of-order packet rates
- websocket reconnects/errors
- call completion and failure reasons

It is also enough to preserve call and conversation evidence for later inspection:

- transient transcript deltas
- completed user and assistant turns
- interruption events
- conversation command requested/applied/failed events

Prometheus and Grafana remain mostly transport- and runtime-quality tools. Richer conversation-quality inspection now lives in the SSE event traces under `observability/artifacts/event-trace/`.
