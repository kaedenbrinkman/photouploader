#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PYTHONPATH="${ROOT}/worker"
if [[ -z "${LIGHTCRAFT_BIN:-}" || ! -x "${LIGHTCRAFT_BIN}" ]]; then
  echo "LIGHTCRAFT_BIN not set or not executable — skipping LightCraft smoke"
  exit 0
fi
cd "${ROOT}/worker"
pytest -q tests/test_lightcraft_smoke.py -m integration
