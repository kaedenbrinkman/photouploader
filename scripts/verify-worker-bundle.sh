#!/usr/bin/env bash
# Verify tarball layout: offline venv install + import worker.main (AL2023 / Python 3.9).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${ROOT}/dist"
BUNDLE="${DIST}/photopipeline-worker.tar.gz"
TARGET_PY="${TARGET_PY:-3.9}"

if [[ ! -f "$BUNDLE" ]]; then
  echo "missing $BUNDLE — run scripts/build-worker-bundle.sh first" >&2
  exit 1
fi

INSTALL_ROOT="$(mktemp -d)"
trap 'rm -rf "$INSTALL_ROOT"' EXIT
mkdir -p "$INSTALL_ROOT"
tar -xzf "$BUNDLE" -C "$INSTALL_ROOT"

PY="python${TARGET_PY}"
if ! command -v "$PY" >/dev/null 2>&1; then
  PY="python3"
fi

VENV="${INSTALL_ROOT}/venv"
"$PY" -m venv "$VENV"
"${VENV}/bin/pip" install --no-cache-dir --upgrade pip
VENDOR="${INSTALL_ROOT}/photopipeline-worker/vendor"
test -d "$VENDOR" && test -n "$(ls -A "$VENDOR")"
test -d "${INSTALL_ROOT}/photopipeline-worker/gphotos"

"${VENV}/bin/pip" install --no-cache-dir --no-index --find-links="$VENDOR" \
  -r "${INSTALL_ROOT}/photopipeline-worker/requirements.txt"

(
  cd "${INSTALL_ROOT}/photopipeline-worker"
  "${VENV}/bin/python" -c "import worker.main"
)

echo "worker bundle import OK (Python ${TARGET_PY})"
