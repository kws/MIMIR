# Observability assets

This folder provides runtime-comparison observability for the orchestrator and media bridge.

## Metrics endpoints

- Orchestrator: `GET /metrics`
- Media bridge: `GET /metrics`

The orchestrator emits structured logs with `call_id` and `bridge_session_id` where available. The media bridge currently exposes lifecycle state primarily through metrics and SSE events.

## Runtime swap evidence model

Use the `runtime` label on media bridge telemetry to compare:

- inbound-call receipt to active-media latency
- first-audio latency
- RTP packet loss / jitter
- websocket reconnect and error rates
- media bridge call completion and failure reasons

## Telemetry ingestion

Media runtimes can post periodic quality samples:

`POST /v1/media/sessions/{session_id}/telemetry`

Payload fields:

- `runtime`
- `packet_loss_pct`
- `jitter_ms`
- `ws_reconnects`
- `ws_errors`
- `first_audio_latency_ms`

## Assets

- `grafana/call-runtime-evidence-dashboard.json`
- `prometheus/slo-rules.yaml`
