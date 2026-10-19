#!/usr/bin/env bash
# CI: exercise AL2023 bootstrap venv + vendored pip install (no PyPI, no system pip -r).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIST="${ROOT}/dist"
BUNDLE="${DIST}/photopipeline-worker.tar.gz"

if [[ ! -f "$BUNDLE" ]]; then
  echo "missing $BUNDLE — run scripts/build-worker-bundle.sh first" >&2
  exit 1
fi

INSTALL_ROOT="${INSTALL_ROOT:-/tmp/photopipeline-bootstrap-test}"
VENV="${INSTALL_ROOT}/venv"
rm -rf "$INSTALL_ROOT"
mkdir -p "$INSTALL_ROOT"
tar -xzf "$BUNDLE" -C "$INSTALL_ROOT"

# Simulate RPM-owned requests (system pip install -r would fail on AL2023).
python3 -m pip install --no-cache-dir 'requests==2.25.1' 2>/dev/null || true

python3 -m venv "$VENV"
"${VENV}/bin/pip" install --no-cache-dir --upgrade pip
VENDOR="${INSTALL_ROOT}/photopipeline-worker/vendor"
test -d "$VENDOR" && test -n "$(ls -A "$VENDOR")"

"${VENV}/bin/pip" install --no-cache-dir --no-index --find-links="$VENDOR" \
  -r "${INSTALL_ROOT}/photopipeline-worker/requirements.txt"

"${VENV}/bin/python" -c "import boto3, requests, google.auth; print('deps ok', requests.__version__)"
test -d "${INSTALL_ROOT}/photopipeline-worker/gphotos"
(
  cd "${INSTALL_ROOT}/photopipeline-worker"
  "${VENV}/bin/python" -c "import worker.main"
)

bash -n "${ROOT}/infra/lambda/ec2-userdata-bootstrap.sh"
bash -n "${ROOT}/worker/ami/ec2-install-from-s3.sh"

echo "AL2023 bootstrap pip/venv step OK"
