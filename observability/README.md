# Observability assets

This folder provides the local observability stack for the orchestrator and media bridge test bed.

## Metrics endpoints

- Orchestrator: `GET /metrics`
- Media bridge: `GET /metrics`

The orchestrator emits structured logs with `call_id` and `bridge_session_id` where available. The media bridge currently exposes lifecycle state primarily through metrics and SSE events.

## Compose stack

`services/docker-compose.yml` now includes:

- `prometheus` for scraping `/metrics`
- `grafana` with a provisioned MIMIR dashboard
- `event-trace` to capture orchestrator and media SSE streams into newline-delimited JSON

Default local endpoints:

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

The event trace sidecar writes:

- `observability/artifacts/event-trace/orchestrator-call-events.ndjson`
- `observability/artifacts/event-trace/media-bridge-events.ndjson`

## Runtime swap evidence model

Use the `runtime` label on media bridge telemetry to compare:

- inbound-call receipt to active-media latency
- first-audio latency
- RTP packet loss / jitter
- RTP sender lag and jitter-buffer depth
- RTP playout buffer depth, stale audio drops, underruns, outbound packet count, and packet spacing
- duplicate, late, missing, and out-of-order packet rates
- websocket reconnect and error rates
- media bridge call completion and failure reasons

For live calls, this is still the main call-quality evidence path today. Conversation-quality evidence now arrives through the SSE traces rather than the metrics path: transcript deltas are transient event-stream data, while completed turns, interruptions, and conversation command lifecycle events are durable.

That gives the test bed two complementary layers:

- Prometheus/Grafana for transport and runtime quality
- NDJSON event traces for conversation flow and steering evidence

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

Live RTP telemetry events may also include:

- `playout_depth_ms`
- `playout_max_depth_ms`
- `playout_enqueued_ms`
- `playout_dropped_ms`
- `playout_truncated_ms`
- `playout_underruns`
- `outbound_packets_sent`
- `outbound_packet_spacing_ms_avg`
- `outbound_packet_spacing_ms_max`

## Conversation event traces

`GET /v1/media/events` and `GET /v1/call-events` now carry additive `conversation.*` events.

- Transcript delta events use top-level `transient: true` and are forwarded live without durable storage in the orchestrator.
- Completed turns, interruptions, and conversation command lifecycle events use `transient: false` and are stored durably in the orchestrator projection/history.

This means the event-trace sidecar is now the best local source for reconstructing what happened in a call at the turn level, even when Grafana is focused on RTP timing and runtime health.

## Assets

- `grafana/dashboards/call-runtime-evidence-dashboard.json`
- `grafana/provisioning/datasources/datasources.yml`
- `grafana/provisioning/dashboards/dashboards.yml`
- `prometheus/prometheus.yml`
- `prometheus/slo-rules.yaml`
