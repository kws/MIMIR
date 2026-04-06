# MIMIR 🧙‍♂️

Python-first, inbound-only SIP-to-AI voice bridge.

MIMIR lets you route inbound SIP calls to AI personas (for example: historical scientists) using a clean two-service architecture:

- **SIP Flow Handler** (`services/sip-flow-handler`) handles inbound invite policy, call state, and orchestration.
- **Media Bridge** (`services/media-bridge`) handles media-session lifecycle, runtime routing, and telemetry.

## Project layout

- `services/sip-flow-handler` — FastAPI service for SIP-side policy + call orchestration.
- `services/media-bridge` — FastAPI service for media control/session runtime.
- `contracts/` — OpenAPI + protobuf controller contracts.
- `observability/` — Prometheus/Grafana artifacts.

## Requirements

- Python 3.11+
- `pip` (or your preferred Python package manager)
- OpenAI API key (if you connect a real AI runtime)

## Quickstart (local)

Run both services in separate terminals.

```bash
# terminal 1
cd services/media-bridge
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8081
```

```bash
# terminal 2
cd services/sip-flow-handler
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
MEDIA_BRIDGE_URL=http://localhost:8081 uvicorn app.main:app --host 0.0.0.0 --port 8080
```

## Docker Compose

```bash
cd services
docker compose up --build
```

Services:

- SIP Flow Handler: `http://localhost:8080`
- Media Bridge: `http://localhost:8081`

## Inbound-only policy

Outbound origination is explicitly rejected. The SIP handler and media bridge both enforce inbound-only behavior and emit auditable reasons for rejections/failures.

## Observability

Both services expose `GET /metrics` for Prometheus scraping.

See:

- `observability/README.md`
- `observability/prometheus/slo-rules.yaml`
- `observability/grafana/call-runtime-evidence-dashboard.json`

## Contracts

- OpenAPI: `contracts/media-control.openapi.yaml`
- Protobuf: `contracts/media-control.proto`

## License

MIT (see `LICENSE`).
