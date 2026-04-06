# MIMIR Service Extraction Plan (Deployable Split)

This directory introduces a strict split between SIP control and media runtime.

## Services

1. **SIP Flow Handler** (`services/sip-flow-handler`)
   - Owns SIP registration state, inbound INVITE policy checks, and call state machine.
   - Talks to Media Bridge exclusively over network control APIs.

2. **Media Bridge** (`services/media-bridge`)
   - Owns RTP/media lifecycle, AI websocket lifecycle, and audio processing runtime hooks.
   - Exposes HTTP control APIs and an SSE event stream. A matching gRPC contract is in `contracts/media-control.proto`.

## Explicit Backend Control Interface

- HTTP Control API: `contracts/media-control.openapi.yaml`
- Event Stream: `GET /v1/media/events` (SSE)
- gRPC Contract: `contracts/media-control.proto`

No in-process object references are permitted between SIP flow logic and media runtime logic; only remote API calls/events.

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

## Build containers

```bash
docker build -t mimir-media-bridge ./services/media-bridge
docker build -t mimir-sip-flow-handler ./services/sip-flow-handler
```
