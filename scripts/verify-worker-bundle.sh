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

resolve_host_python() {
  local candidate="python${TARGET_PY}"
  if command -v "$candidate" >/dev/null 2>&1; then
    echo "$candidate"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    local ver
    ver="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
    if [[ "$ver" == "$TARGET_PY" ]]; then
      echo python3
      return 0
    fi
  fi
  return 1
}

find_target_python() {
  local py=""
  if py="$(resolve_host_python)"; then
    echo "$py"
    return 0
  fi
  if command -v uv >/dev/null 2>&1; then
    py="$(uv python find "${TARGET_PY}" 2>/dev/null || true)"
    if [[ -n "$py" ]]; then
      echo "$py"
      return 0
    fi
  fi
  return 1
}

INSTALL_ROOT="$(mktemp -d)"
trap 'rm -rf "$INSTALL_ROOT"' EXIT
mkdir -p "$INSTALL_ROOT"
tar -xzf "$BUNDLE" -C "$INSTALL_ROOT"

PY="$(find_target_python)" || {
  echo "Python ${TARGET_PY} required for bundle verify (install python${TARGET_PY} or uv)" >&2
  exit 1
}

VENV="${INSTALL_ROOT}/venv"
"$PY" -m venv "$VENV"
"${VENV}/bin/pip" install --no-cache-dir --upgrade pip
VENDOR="${INSTALL_ROOT}/photopipeline-worker/vendor"
test -d "$VENDOR" && test -n "$(ls -A "$VENDOR")"
test -d "${INSTALL_ROOT}/photopipeline-worker/gphotos"

if compgen -G "$VENDOR/urllib3-2.*.whl" >/dev/null; then
  echo "urllib3 2.x wheel in bundle — incompatible with Python ${TARGET_PY} botocore" >&2
  exit 1
fi

"${VENV}/bin/pip" install --no-cache-dir --no-index --find-links="$VENDOR" \
  -r "${INSTALL_ROOT}/photopipeline-worker/requirements.txt"

(
  cd "${INSTALL_ROOT}/photopipeline-worker"
  "${VENV}/bin/python" -c "import worker.main"
)

echo "worker bundle import OK (Python ${TARGET_PY})"
