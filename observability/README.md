# Observability assets

This folder provides runtime-comparison observability for SIP handler + Media Bridge.

## Metrics endpoints

- SIP handler: `GET /metrics`
- Media bridge: `GET /metrics`

Both services emit structured logs that include `call_id` and `bridge_session_id` keys where available.

## Runtime swap evidence model

Use the `runtime` label (for example, `python` vs `rust`/`go`) on Media Bridge telemetry to compare:

- INVITE → answer latency
- first-audio latency
- RTP packet loss / jitter
- websocket reconnect and error rates
- call completion and failure reasons

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
