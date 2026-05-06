# 010 - Improve Audio Quality

## Goal

Make the live RTP media path resilient to bursty upstream model audio, network jitter, scheduler delays, and sample-rate conversion artifacts so calls sound stable before deeper runtime, language-model, or service-language changes are evaluated.

The most important invariant is:

> Model audio arrival rate must never determine RTP send rate.

The RTP bridge owns packet timing. Upstream runtime audio can arrive in bursts, gaps, or uneven chunks; the bridge must convert that into stable 20 ms RTP playout without packet storms.

## Current Context

- The media bridge currently owns live RTP handling in `services/media-bridge/mimir/mediabridge/live_rtp.py`.
- Outbound audio is packetized by `RtpOutboundStream` in `services/media-bridge/mimir/mediabridge/rtp.py`.
- Outbound RTP is already paced at 20 ms intervals, but the playout buffer is implicit and unbounded.
- Inbound jitter handling is currently a fixed packet window.
- Resampling is currently simple linear interpolation in `services/media-bridge/mimir/mediabridge/audio.py`.
- RTP telemetry is exposed through media bridge metrics and normalized `media.telemetry` events.
- The current live call path is `g711_ulaw` at 8 kHz, with upstream model audio commonly arriving at a higher sample rate.

Relevant implementation points:

- `LiveRtpBridge._handle_runtime_event(...)` appends runtime audio into `self.outbound_stream`.
- `LiveRtpBridge._sender_loop(...)` sleeps until the next 20 ms tick, pulls one packet, and sends it with `transport.sendto(...)`.
- `LiveRtpBridge._buffer_inbound_payload(...)` reorders inbound RTP by sequence number.
- `RtpOutboundStream.enqueue_audio(...)` resamples, encodes to u-law, and appends bytes to an internal `bytearray`.
- `RtpOutboundStream.next_packet(...)` removes one 160-byte u-law frame and builds an RTP packet.

## Problems To Solve

### 1. Unbounded Outbound Latency

The current outbound stream is a raw byte buffer. If the runtime produces a large amount of audio quickly, the bridge will pace the output correctly, but it may accumulate seconds of buffered audio. That avoids packet storms but can make the assistant feel delayed or stale.

Desired behavior:

- Bound playout latency.
- Prefer dropping or truncating stale assistant audio over letting the caller hear old audio late.
- Make buffer depth visible in telemetry.

### 2. Packet Timing Evidence

The bridge tracks sender lag today, but not packet spacing or playout-buffer behavior in enough detail to prove quality.

Desired behavior:

- Track outbound packet spacing.
- Track sender lag against the target send time.
- Track underruns.
- Track high-water drops or truncations.
- Make these visible in Prometheus and `media.telemetry`.

### 3. Inbound Jitter Window Is Fixed

Inbound RTP reordering currently waits for a fixed small packet window. This is useful, but it should be configurable and tested against realistic disorder and loss patterns.

Desired behavior:

- Keep the current simple algorithm for the first implementation pass unless tests prove it is insufficient.
- Move magic constants into named settings.
- Add tests for jitter spikes and packet bursts.
- Prepare a later adaptive jitter buffer if needed.

### 4. Basic Resampling

The current linear interpolation resampler is simple and dependency-free, but downsampling model output to 8 kHz can alias and sound rough.

Desired behavior:

- Isolate the resampler behind a clear function/interface.
- Prefer a better quality resampler when available.
- Keep dependency and container impact explicit.
- Preserve deterministic fallback behavior for tests.

## Non-Goals

- Do not move RTP media through NATS.
- Do not change orchestrator ownership boundaries.
- Do not rewrite the media bridge in Go or Rust as part of this task.
- Do not add SIP registration, SDP negotiation, NAT traversal, or provider signaling to the orchestrator.
- Do not implement wideband telephony codecs unless explicitly split into another feature.
- Do not make conversation steering depend on audio-quality internals.

## Target Design

### Outbound Flow

```text
runtime audio event
  -> normalize/resample to RTP sample rate
  -> encode to G.711 u-law
  -> append to bounded playout buffer
  -> sender loop drains one 20 ms frame per tick
  -> RTP packet sent to remote target
```

The runtime can enqueue arbitrarily sized audio chunks. The playout buffer is responsible for:

- converting byte depth to milliseconds
- enforcing high-water limits
- recording drops/truncations
- giving the sender loop exactly one frame at a time

### Inbound Flow

```text
RTP packet received
  -> parse and validate
  -> reject wrong remote/source/payload/size
  -> reorder by sequence number
  -> release ordered frames to runtime input
  -> record jitter, late, missing, duplicate, and buffer-depth telemetry
```

Inbound jitter handling should remain inside `LiveRtpBridge`; the orchestrator should only see normalized telemetry and lifecycle events.

## Proposed Code Changes

### 1. Add `RtpPlayoutBuffer`

Create an explicit outbound playout buffer in `services/media-bridge/mimir/mediabridge/rtp.py`.

Suggested shape:

```python
@dataclass(slots=True)
class RtpPlayoutBufferSnapshot:
    depth_ms: float
    max_depth_ms: float
    dropped_ms: float
    truncated_ms: float
    underruns: int
    enqueued_ms: float


@dataclass(slots=True)
class RtpPlayoutBuffer:
    sample_rate_hz: int = G711_ULAW_SAMPLE_RATE_HZ
    payload_bytes: int = G711_ULAW_PAYLOAD_BYTES
    max_depth_ms: int = 1200
    target_prefill_ms: int = 60
    stale_policy: Literal["drop_oldest", "drop_newest", "truncate_oldest"] = "drop_oldest"
```

Core methods:

- `enqueue_audio(audio: PcmAudio) -> None`
- `clear() -> None`
- `next_payload() -> bytes | None`
- `depth_ms() -> float`
- `snapshot() -> RtpPlayoutBufferSnapshot`

The first implementation can store encoded u-law bytes internally, matching the current `RtpOutboundStream` behavior. A later Go/Rust implementation can use a ring buffer with the same semantics.

### 2. Keep RTP Packetization Separate

Refactor `RtpOutboundStream` so it either:

- owns a `RtpPlayoutBuffer`, or
- becomes only the packet sequencer/timestamp builder over a playout buffer.

Preferred shape:

```text
RtpPlayoutBuffer
  - owns encoded audio bytes and drop policy

RtpOutboundStream
  - owns payload type, SSRC, sequence number, timestamp
  - asks buffer for next 20 ms payload
  - builds RTP packet
```

This separation makes packet timing easier to reason about and easier to port to Go/Rust later.

### 3. Add Bounded Buffer Policy

Add environment-backed settings in the media bridge:

- `MEDIA_BRIDGE_PLAYOUT_MAX_DEPTH_MS`, default `1200`
- `MEDIA_BRIDGE_PLAYOUT_TARGET_PREFILL_MS`, default `60`
- `MEDIA_BRIDGE_PLAYOUT_STALE_POLICY`, default `drop_oldest`
- `MEDIA_BRIDGE_INBOUND_JITTER_BUFFER_PACKETS`, default `3`

Policy details:

- `drop_oldest`: when buffer exceeds the max depth, discard oldest queued audio until the buffer is under the limit.
- `drop_newest`: reject newly arrived excess audio and preserve currently queued speech.
- `truncate_oldest`: trim from the front only enough to fit the new chunk.

Initial recommendation: use `drop_oldest`. For live assistant audio, old queued speech is usually less valuable than keeping the call responsive.

### 4. Add Underrun Handling

Decide one of two policies for no outbound audio at a tick:

1. No-send: do not emit a packet when there is no audio.
2. Silence-fill: emit a 20 ms u-law silence packet.

The first implementation can keep current no-send behavior, but the policy should be explicit and measured.

Add:

- `MEDIA_BRIDGE_PLAYOUT_UNDERRUN_POLICY`, default `no_send`
- supported values: `no_send`, `silence`

For PBX fixture compatibility, test both if possible. If Asterisk behaves better with continuous packets during assistant speech gaps, switch the default to `silence`.

### 5. Harden Sender Timing

Update `LiveRtpBridge._sender_loop(...)` to record:

- target send time
- actual send time
- lag in milliseconds
- interval since previous packet
- number of skipped ticks due to scheduler delay

Sender loop expectations:

- It must never catch up by sending multiple RTP packets in a tight loop.
- If the scheduler wakes late, send at most one packet and schedule the next tick relative to `now`.
- NATS, HTTP, logging, and expensive event emission must not happen in the hot send loop.

Current code already mostly follows this shape by resetting `next_send_at` when late. Preserve that behavior and add tests around it.

### 6. Improve Resampling Behind A Boundary

Refactor `PcmAudio.resample(...)` so the implementation can be swapped without touching RTP logic.

Possible implementation options:

1. Keep current linear resampler as fallback.
2. Add a better standard-library-only decimation path for common `24_000 -> 8_000`.
3. Add an optional high-quality dependency if acceptable for the project.

For this task, prefer a small, testable improvement without introducing a large native dependency unless explicitly approved.

Candidate first pass:

- Add a dedicated `downsample_24k_to_8k_pcm16_mono(...)`.
- Apply a simple low-pass FIR or moving-window filter before decimating.
- Keep existing generic linear resampler for other rates.

Test it with deterministic sine-wave fixtures:

- 1 kHz tone survives downsampling.
- high-frequency tone above 4 kHz is attenuated before conversion to 8 kHz.
- duration remains stable within a small tolerance.

### 7. Add Telemetry

Extend `RtpTelemetrySnapshot` or add an outbound-specific snapshot.

Suggested fields:

- `playout_depth_ms`
- `playout_max_depth_ms`
- `playout_enqueued_ms`
- `playout_dropped_ms`
- `playout_truncated_ms`
- `playout_underruns`
- `outbound_packets_sent`
- `outbound_packet_spacing_ms_avg`
- `outbound_packet_spacing_ms_max`
- `sender_lag_ms_avg`
- `sender_lag_ms_max`

Prometheus metrics:

- `media_bridge_rtp_playout_depth_ms`
- `media_bridge_rtp_playout_dropped_ms_total`
- `media_bridge_rtp_playout_truncated_ms_total`
- `media_bridge_rtp_playout_underrun_total`
- `media_bridge_rtp_outbound_packets_total`
- `media_bridge_rtp_outbound_packet_spacing_ms`
- existing `media_bridge_rtp_sender_lag_ms`

Also include the new fields in `media.telemetry` events so event traces can explain audible call behavior after the fact.

### 8. Update Observability Docs

If metric names or lifecycle semantics change, update:

- `README.md`
- `services/README.md`
- `observability/README.md`
- `observability/prometheus/slo-rules.yaml`
- `observability/grafana/dashboards/call-runtime-evidence-dashboard.json`

Only update dashboards once metrics are implemented and stable.

## Proposed Scope

1. Implement bounded outbound playout buffering.
2. Harden sender-loop timing and telemetry.
3. Make inbound jitter settings configurable and better tested.
4. Improve or isolate resampling quality.
5. Extend metrics, event telemetry, and observability docs.

## Implementation Plan

### Phase 1 - Tests That Define The Invariants

Add tests before or alongside the buffer changes:

- `test_outbound_runtime_burst_is_paced`
  - enqueue enough runtime audio for many packets at once
  - verify packets are not emitted in a tight burst
  - verify sequence numbers and timestamps advance by one frame

- `test_outbound_playout_buffer_is_bounded`
  - configure a small max depth
  - enqueue more audio than the max
  - verify depth is capped and dropped/truncated telemetry is recorded

- `test_outbound_underrun_is_measured`
  - run sender loop without queued audio
  - verify underrun count increments according to policy

- `test_sender_loop_does_not_catch_up_with_packet_storm`
  - simulate late wake-up if practical by extracting timing math into a testable helper
  - verify at most one packet is sent per loop iteration

- `test_inbound_jitter_window_from_settings`
  - configure jitter window
  - verify missing packet behavior changes only as expected

- `test_24k_to_8k_downsample_preserves_duration`
  - deterministic PCM fixture
  - verify output duration and sample count

### Phase 2 - Playout Buffer

Implement `RtpPlayoutBuffer` and route `RtpOutboundStream.enqueue_audio(...)` through it.

Keep the public behavior of `RtpOutboundStream.next_packet(...)` stable for existing tests.

Add snapshot support:

```python
snapshot = stream.playout_snapshot()
```

or:

```python
snapshot = stream.buffer.snapshot()
```

Choose the API that keeps `LiveRtpBridge` simple.

### Phase 3 - Sender Telemetry

Extend telemetry tracking so `LiveRtpBridge._sender_loop(...)` can record outbound timing without doing heavy work in the loop.

Recommended approach:

- update in-memory counters in the hot path
- emit metrics and `media.telemetry` from existing telemetry hooks
- avoid publishing one event per RTP packet

### Phase 4 - Configuration

Read playout and jitter settings near media bridge backend/session creation, not in deeply nested loops.

Suggested settings object:

```python
@dataclass(frozen=True, slots=True)
class RtpQualitySettings:
    playout_max_depth_ms: int
    playout_target_prefill_ms: int
    playout_stale_policy: str
    playout_underrun_policy: str
    inbound_jitter_buffer_packets: int
```

Pass settings into `LiveRtpBridge` or `RtpOutboundStream` explicitly. Avoid global environment reads inside RTP helpers.

### Phase 5 - Resampling

Add a focused resampling improvement after playout behavior is covered. This should be a separate commit if possible, because packet timing and sample quality are different failure modes.

Keep both:

- deterministic fallback resampler
- higher quality common-path downsampler

### Phase 6 - Observability

Wire new telemetry into:

- media bridge Prometheus metrics
- `media.telemetry` events
- docs and dashboards where useful

Prefer adding metrics first, then dashboard panels once field names are stable.

## Suggested File-Level Work

### `services/media-bridge/mimir/mediabridge/rtp.py`

- Add `RtpPlayoutBuffer`.
- Add `RtpPlayoutBufferSnapshot`.
- Add outbound telemetry fields or a new outbound telemetry class.
- Refactor `RtpOutboundStream` to use the playout buffer.
- Add packet spacing helper methods if they naturally belong here.

### `services/media-bridge/mimir/mediabridge/live_rtp.py`

- Replace `INBOUND_JITTER_BUFFER_PACKETS` usage with settings.
- Pass playout settings into `RtpOutboundStream`.
- Update `_sender_loop(...)` to record packet spacing, underruns, and lag.
- Ensure hot-path updates stay cheap.

### `services/media-bridge/mimir/mediabridge/audio.py`

- Isolate resampling implementation.
- Add a better `24_000 -> 8_000` path if no new dependency is approved.
- Add tests for duration, clipping, and high-frequency attenuation.

### `services/media-bridge/mimir/mediabridge/main.py`

- Add Prometheus metrics.
- Include playout fields in `media.telemetry`.
- Keep metric labels consistent with existing `runtime` labeling.

### `services/media-bridge/mimir/mediabridge/backends.py`

- Build and pass RTP quality settings into live RTP sessions if this is the cleanest injection point.

### `services/media-bridge/tests/`

- Extend `test_rtp.py` for playout buffer behavior.
- Extend `test_live_rtp.py` for pacing and underrun behavior.
- Extend `test_audio.py` for resampling quality.

## Configuration Defaults

Initial defaults:

```text
MEDIA_BRIDGE_PLAYOUT_MAX_DEPTH_MS=1200
MEDIA_BRIDGE_PLAYOUT_TARGET_PREFILL_MS=60
MEDIA_BRIDGE_PLAYOUT_STALE_POLICY=drop_oldest
MEDIA_BRIDGE_PLAYOUT_UNDERRUN_POLICY=no_send
MEDIA_BRIDGE_INBOUND_JITTER_BUFFER_PACKETS=3
```

Validation:

- `PLAYOUT_MAX_DEPTH_MS` must be at least one RTP frame.
- `PLAYOUT_TARGET_PREFILL_MS` must be less than or equal to max depth.
- stale policy must be one of the supported values.
- underrun policy must be one of the supported values.
- inbound jitter buffer packets must be non-negative and bounded to a sane maximum, for example `0..50`.

## Testing Strategy

Run from the repository root:

```bash
uv run --package mimir-media-bridge pytest
uv run --all-packages ruff check .
```

Focused tests:

```bash
uv run --package mimir-media-bridge pytest services/media-bridge/tests/test_rtp.py
uv run --package mimir-media-bridge pytest services/media-bridge/tests/test_live_rtp.py
uv run --package mimir-media-bridge pytest services/media-bridge/tests/test_audio.py
```

Manual/integration checks:

1. Start the compose stack.
2. Place a PBX fixture call.
3. Confirm assistant audio is paced and not bursty.
4. Inspect Prometheus metrics for sender lag, playout depth, underruns, and drops.
5. Inspect `observability/artifacts/event-trace/media-bridge-events.ndjson` for matching `media.telemetry` fields.

## Success Signals

- Packet storms are impossible by construction.
- Playout latency is bounded.
- The caller does not hear several seconds of stale assistant audio after an interruption.
- Sender lag remains low under normal load.
- Underruns and drops are visible when they happen.
- Resampling does not introduce obvious aliasing in the common OpenAI Realtime output path.
- The implementation is still portable to a future Go or Rust media bridge.

## Acceptance Criteria

- A large upstream audio burst cannot produce an RTP packet storm.
- RTP packets are emitted at the configured frame interval under normal scheduler conditions.
- Buffer depth is bounded and observable.
- Stale audio is dropped or truncated according to an explicit policy.
- Tests cover burst input, underrun, jitter, packet loss, late packets, and packet reordering.
- Resampling changes preserve duration and reduce obvious downsampling artifacts for the common runtime output path.
- New metrics are included in `media.telemetry` and Prometheus where appropriate.
- Existing media bridge tests continue to pass.

## Open Decisions

- Should the underrun default be `no_send` or `silence` for the PBX fixture and future adapters?
- Is an optional native/audio dependency acceptable for higher-quality resampling, or should the first pass stay pure Python?
- Should stale audio prefer dropping oldest queued speech or rejecting newly arrived speech?
- What is the target maximum assistant playout latency: 800 ms, 1200 ms, or another value?
- Should jitter buffering become adaptive in this feature, or remain a follow-up after better telemetry is available?

## Future Follow-Ups

- NATS event spine for media telemetry and call-quality traces.
- Go or Rust media bridge prototype with a dedicated RTP scheduler thread.
- Wideband codec support if the telephony edge can support it.
- Adaptive jitter buffer based on observed jitter and packet loss.
- Better echo/barge-in handling if upstream runtime and telephony path support it.

## Notes

This work should stay inside the media bridge. The orchestrator should not become aware of RTP timing details beyond normalized telemetry and lifecycle events.
