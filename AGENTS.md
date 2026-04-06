# AGENTS

## Current architecture

MIMIR is currently being rebuilt around a provider-agnostic Python core:

- `services/orchestrator` owns normalized inbound-call intake, policy checks, persona selection, call state, and audit/event history.
- `services/media-bridge` owns media session lifecycle, runtime routing, and media telemetry.
- Telephony edges such as Asterisk, Twilio, or generic SIP providers are expected to arrive later as adapter-specific integrations and should not be baked into the core service model.

## Working assumptions for agents

- Treat the orchestrator as the control-plane hub.
- Treat the media bridge as a downstream media service.
- Do not reintroduce fake SIP registration or pretend the orchestrator is itself a SIP stack.
- Do not make the media bridge subscribe back into orchestrator state; keep the dependency direction one-way where possible.
- Keep provider-specific concerns at the edge adapter boundary.
- Keep NAT traversal, RTP relay, registration, and provider signaling out of the orchestrator unless the work is explicitly about adapter contracts.

## Python tooling baseline

This repo uses a `uv` workspace for Python monorepo coordination:

- Root workspace config: `pyproject.toml`
- Service project configs:
  - `services/orchestrator/pyproject.toml`
  - `services/media-bridge/pyproject.toml`

Use these commands from the repository root unless a task explicitly says otherwise:

```bash
uv sync --all-packages --dev
uv run --all-packages ruff format .
uv run --all-packages ruff check .
uv run --package mimir-orchestrator pytest
uv run --package mimir-media-bridge pytest
```

Testing expectations:

- Unit tests use `pytest`.
- Coverage is enabled via `pytest-cov` defaults in each service `pyproject.toml`.

Packaging expectations:

- New Python sub-projects should define `pyproject.toml` with `hatchling` as the build backend.
- Keep provider-agnostic service boundaries intact when organizing packages.

## Legacy reference

The old Java implementation is kept for reference only under:

- `reference/java-legacy/`

That directory contains the pre-Python implementation restored from commit `59030e0cbdf5b210489a6a9f7e502d5b41785261`.

Important:

- It is reference material, not the active architecture.
- It must not be treated as the active architecture or wired back into the core Python service model.
- It can be mined for behavior, protocol handling ideas, RTP/OpenAI bridge logic, and test scenarios while the Python implementation is rebuilt.

## Compose stack

The default compose stack is intentionally simple:

- `services/pbx` as a PBX fixture for one-click local and continuous testing
- `services/orchestrator`
- `services/media-bridge`

`services/pbx` is included as a fixture, not because Asterisk is the architecture center. Keep adapter boundaries provider-agnostic even when the PBX fixture is present.

## Contracts

When changing service boundaries, keep these aligned:

- `contracts/media-control.openapi.yaml`
- `contracts/media-control.proto`
- `README.md`
- `services/README.md`
- `observability/*` if metric names or lifecycle semantics change

## Editing guidance

- Prefer evolving the Python services and docs over reviving legacy Java paths.
- If you need historical behavior, inspect `reference/java-legacy/` first and port the intent, not the old architecture wholesale.
- Avoid adding “temporary” compatibility layers that make the orchestrator look like a SIP adapter.
