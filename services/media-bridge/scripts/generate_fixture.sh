#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)/fixtures"
OUTPUT_PATH="${1:-${FIXTURE_DIR}/hello-mimir.wav}"

mkdir -p "${FIXTURE_DIR}"

if command -v say >/dev/null 2>&1; then
  say "Hello MIMIR. Can you explain what a black hole is in two short sentences?" \
    --data-format=LEI16@16000 \
    -o "${OUTPUT_PATH}"
  echo "Generated ${OUTPUT_PATH}"
  exit 0
fi

echo "Unable to generate a speech fixture automatically because 'say' is not available." >&2
echo "Provide a mono 16-bit PCM WAV file at ${OUTPUT_PATH} instead." >&2
exit 1
