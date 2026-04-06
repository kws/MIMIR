# MIMIR 🧙‍♂️

Python-first, inbound-only SIP-to-AI voice bridge.

MIMIR lets you route inbound SIP calls to AI personas (for example: historical scientists) using a clean three-service stack:

- **SIP Flow Handler** (`services/sip-flow-handler`) handles inbound invite policy, call state, and orchestration.
- **Media Bridge** (`services/media-bridge`) handles media-session lifecycle, runtime routing, and telemetry.
- **Asterisk PBX** (`services/pbx`) provides out-of-the-box SIP registrations and extension dialing.

## Project layout

- `services/sip-flow-handler` — FastAPI service for SIP-side policy + call orchestration.
- `services/media-bridge` — FastAPI service for media control/session runtime.
- `services/pbx` — Containerized Asterisk PBX with pre-configured users/extensions.
- `services/config/ai-profiles.json` — default scientist AI profile mappings for extensions.
- `contracts/` — OpenAPI + protobuf controller contracts.
- `observability/` — Prometheus/Grafana artifacts.

## Requirements

- Docker + Docker Compose
- OpenAI API key

## Quickstart (single-click stack)

```bash
cd services
cp .env.example .env
# required: OPENAI_API_KEY
# optional: SCIENTIST_MODEL_NAME to test a different model version
docker compose up --build
```

## SIP accounts and extensions

The PBX boots with these accounts:

| Role | Extension | Password |
|---|---:|---|
| Operator | `1000` | `lab1000` |
| Scientist - Arthur C. Clarke | `2001` | `clarke2001` |
| Scientist - Albert Einstein | `2002` | `einstein2002` |
| Scientist - Erwin Schrödinger | `2003` | `schrodinger2003` |
| Scientist - Marie Curie | `2004` | `curie2004` |
| Scientist - Niels Bohr | `2005` | `bohr2005` |
| Scientist - Nikola Tesla | `2006` | `tesla2006` |

Register your SIP client to `localhost:5060/udp` and dial one of the scientist extensions.

## Service endpoints

- SIP Flow Handler: `http://localhost:8080`
- Media Bridge: `http://localhost:8081`
- Asterisk SIP listener: `udp://localhost:5060`
- Asterisk RTP: `udp://localhost:10000-10099`

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
