# 010 - Improve Audio Quality

## Goal

Make the live RTP media path resilient to bursty upstream model audio, network jitter, and scheduler delays so calls sound stable before deeper runtime or language-model changes are evaluated.

## Current Context

- The media bridge currently owns live RTP handling in `services/media-bridge/mimir/mediabridge/live_rtp.py`.
- Outbound audio is packetized by `RtpOutboundStream` in `services/media-bridge/mimir/mediabridge/rtp.py`.
- Outbound RTP is already paced at 20 ms intervals, but the playout buffer is implicit and unbounded.
- Inbound jitter handling is currently a fixed packet window.
- Resampling is currently simple linear interpolation in `services/media-bridge/mimir/mediabridge/audio.py`.

## Proposed Scope

1. Introduce an explicit outbound playout buffer.
   - Track buffer depth in milliseconds.
   - Drain exactly one RTP frame per send tick.
   - Bound maximum buffered audio.
   - Add a stale-audio policy when model output arrives faster than RTP can play it.

2. Harden timed RTP sending.
   - Preserve the invariant that model audio arrival rate never determines RTP send rate.
   - Track sender lag and packet spacing.
   - Keep logging, event publishing, and control-plane work out of the timed send path.

3. Improve jitter behavior.
   - Keep current packet reordering tests.
   - Add tests for jitter spikes, missing packets, late packets, and packet bursts.
   - Consider replacing the fixed inbound jitter window with a configurable or adaptive window.

4. Improve conversion quality.
   - Replace or isolate the current basic resampler.
   - Add fixture-based checks for 24 kHz to 8 kHz downsampling.
   - Preserve G.711 u-law compatibility for the current PBX fixture path.

5. Add call-quality evidence.
   - Export playout buffer depth.
   - Export dropped or truncated outbound audio.
   - Export underruns.
   - Export sender lag p95/p99 if practical.
   - Keep packet loss, jitter, late, duplicate, missing, and out-of-order metrics aligned with observability docs.

## Acceptance Criteria

- A large upstream audio burst cannot produce an RTP packet storm.
- RTP packets are emitted at the configured frame interval under normal scheduler conditions.
- Buffer depth is bounded and observable.
- Stale audio is dropped or truncated according to an explicit policy.
- Tests cover burst input, underrun, jitter, packet loss, late packets, and packet reordering.
- Existing media bridge tests continue to pass.

## Notes

This work should stay inside the media bridge. The orchestrator should not become aware of RTP timing details beyond normalized telemetry and lifecycle events.
