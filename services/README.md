# MIMIR Service Extraction Plan (Deployable Split)

This directory introduces a strict split between SIP control and media runtime for an **inbound-only** telephony system.

## Services

1. **SIP Flow Handler** (`services/sip-flow-handler`)
   - Owns SIP registration state, inbound INVITE policy checks, source/trunk controls, and call state machine.
   - Talks to Media Bridge exclusively over network control APIs.

2. **Media Bridge** (`services/media-bridge`)
   - Owns RTP/media lifecycle, AI websocket lifecycle, and audio processing runtime hooks.
   - Enforces `direction == inbound` before allocating media sessions.
   - Exposes HTTP control APIs and an SSE event stream. A matching gRPC contract is in `contracts/media-control.proto`.

## Explicit Backend Control Interface

- HTTP Control API: `contracts/media-control.openapi.yaml`
- Event Stream: `GET /v1/media/events` (SSE)
- gRPC Contract: `contracts/media-control.proto`

No in-process object references are permitted between SIP flow logic and media runtime logic; only remote API calls/events.

## Ownership and Failure Arbitration Rules

### Termination ownership boundaries

- **SIP Flow Handler owns SIP dialog termination**:
  - Sends final SIP response (`4xx/5xx`) if a call fails before answer.
  - Sends `BYE` (or equivalent finalization) for established dialogs when controller declares failure/timeout.
  - Owns final `CallEnded` projection state publication for signaling-side lifecycle.
- **Media Bridge owns media socket/websocket cleanup**:
  - Closes RTP sockets, websocket sessions, and media workers.
  - Releases media allocation and emits cleanup completion/failure event.
  - Must perform idempotent cleanup even if SIP side has already terminated.
- **Controller arbitrates timeout and failure transitions**:
  - Evaluates timer expirations and asynchronous failure events from either side.
  - Selects terminal state and required compensating actions.
  - Prevents split-brain by issuing a single terminal decision for each `call_id`.

### Timeout matrix (explicit timers and compensating actions)

| Timer | Starts at | Expiry terminal state | Required compensating action |
|---|---|---|---|
| **Media allocation timeout** | INVITE accepted by controller and media allocation requested from Media Bridge | `FAILED_MEDIA_ALLOCATION_TIMEOUT` | Controller commands Media Bridge `release(call_id)`; SIP Flow Handler sends pre-answer failure response (`503 Service Unavailable` equivalent); publish hangup reason `MEDIA_ALLOCATION_TIMEOUT`. |
| **First-audio timeout** | Media Bridge reports session allocated/connected and call is answered | `FAILED_FIRST_AUDIO_TIMEOUT` | Controller commands Media Bridge cleanup; SIP Flow Handler terminates dialog (`BYE` if answered, otherwise `408 Request Timeout` equivalent); propagate hangup reason `FIRST_AUDIO_TIMEOUT` to SIP CDR and event stream. |
| **Idle/no-media timeout** | First audio observed (bi-directional media phase) and idle watchdog armed | `ENDED_IDLE_TIMEOUT` | Controller commands normal teardown; SIP Flow Handler issues `BYE` with normal-call-clear fallback semantics; Media Bridge closes RTP/websocket and marks reason `IDLE_NO_MEDIA_TIMEOUT`. |
| **Graceful shutdown timeout** | Shutdown initiated after terminal decision; waiting for SIP + media confirmation | `ENDED_FORCED_SHUTDOWN` | Controller force-closes remaining resources; SIP Flow Handler force-terminates outstanding dialog leg(s); Media Bridge force-closes sockets/websocket; record hangup reason `GRACEFUL_SHUTDOWN_TIMEOUT` and audit forced cleanup. |

### Compensation invariants

- Every timeout transition must:
  1. Emit a single terminal event with `call_id`, `terminal_state`, and `hangup_reason`.
  2. Trigger both signaling-side and media-side teardown paths (order may vary, both required).
  3. Be safe under retries (idempotent `release/terminate` calls).
- If SIP and media outcomes differ (e.g., SIP already ended but media still alive), controller must treat call as non-terminal until both cleanup confirmations are observed or graceful-shutdown timeout forces closure.
- If any compensating action fails, controller records failure cause and escalates to forced shutdown path.

## Inbound-Only Policy

- Only inbound INVITE paths are supported.
- Outbound call origination APIs are intentionally omitted.
- Any outbound origination attempt must be rejected with an explicit error code and audit log entry.
- Call logging is mandatory to support abuse and fraud investigations.

### Explicit non-goals

- Outbound campaign features.
- Auto-dialer hooks or batch dial integrations.

## Extraction Anchors from Existing Java Monolith

### SIP/control roots (to move into SIP Flow Handler)
- `src/main/java/com/kajsiebert/mimir/openai/OpenAIRealtimeUserAgent.java`
- `src/main/java/com/kajsiebert/mimir/openai/OpenAICallController.java`

### Media roots (to move into Media Bridge)
- `src/main/java/com/kajsiebert/mimir/openai/OpenAIRealtimeBridge.java`
- `src/main/java/com/kajsiebert/mimir/openai/websocket/WebsocketSession.java`
- `src/main/java/com/kajsiebert/mimir/openai/rtp/RTPSession.java`

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
