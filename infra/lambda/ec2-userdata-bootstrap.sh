# Ephemeral worker bootstrap on stock AL2023. Expects env: SHOOT_ID, DYNAMODB_TABLE, BUCKET_NAME,
# WORKER_BUNDLE_KEY, WORKER_BUNDLE_SHA256, LIGHTCRAFT_BINARY_KEY, LIGHTCRAFT_BINARY_SHA256,
# INSTALL_ROOT (optional), LIGHTCRAFT_BIN (optional).
set -euo pipefail

sudo shutdown -h +30 "photopipeline bootstrap backstop" 2>/dev/null || true

INSTALL_ROOT="${INSTALL_ROOT:-/opt/photopipeline}"
LIGHTCRAFT_BIN="${LIGHTCRAFT_BIN:-${INSTALL_ROOT}/lightcraft/lightcraft-cli}"
VENV="${INSTALL_ROOT}/venv"
WORKER_PY="${VENV}/bin/python"
BOOT_FAIL_REPORTED=0

log() { echo "[photopipeline-bootstrap] $*" >&2; }

report_fail() {
  local msg="${1:-bootstrap failed}"
  if [[ "$BOOT_FAIL_REPORTED" -eq 1 ]]; then
    sudo shutdown -c 2>/dev/null || true
    sudo shutdown -h now 2>/dev/null || true
    exit 1
  fi
  BOOT_FAIL_REPORTED=1
  log "FATAL: $msg"
  REGION=$(curl -sf --max-time 2 http://169.254.169.254/latest/meta-data/placement/region || true)
  export AWS_DEFAULT_REGION="${REGION:-us-west-2}"
  export BOOT_MSG="$msg"
  python3 - <<'PY' || log "ddb update failed (continuing shutdown)"
import json, os, subprocess, time

msg = os.environ.get("BOOT_MSG", "bootstrap failed")
ts = str(int(time.time() * 1000))
vals = json.dumps(
    {
        ":f": {"S": "FAILED"},
        ":ph": {"S": "BOOTSTRAP_FAILED"},
        ":e": {"S": msg},
        ":t": {"N": ts},
    }
)
subprocess.run(
    [
        "aws",
        "dynamodb",
        "update-item",
        "--table-name",
        os.environ["DYNAMODB_TABLE"],
        "--key",
        json.dumps(
            {"pk": {"S": f"SHOOT#{os.environ['SHOOT_ID']}"}, "sk": {"S": "META"}}
        ),
        "--update-expression",
        "SET #s = :f, phase = :ph, lastError = :e, updatedAt = :t",
        "--expression-attribute-names",
        json.dumps({"#s": "state"}),
        "--expression-attribute-values",
        vals,
    ],
    check=False,
)
PY
  sudo shutdown -c 2>/dev/null || true
  sudo shutdown -h now 2>/dev/null || true
  exit 1
}

on_err() {
  report_fail "bootstrap command failed at line ${1:-unknown}"
}
trap 'on_err $LINENO' ERR

cancel_bootstrap_backstop() {
  sudo shutdown -c 2>/dev/null || true
}

arm_worker_idle_backstop() {
  sudo shutdown -h +120 "photopipeline worker idle backstop" 2>/dev/null || true
}

dnf install -y aws-cli python3 tar 2>/dev/null || yum install -y aws-cli python3 tar

mkdir -p "$INSTALL_ROOT"
BUNDLE=/tmp/worker-bundle.tar.gz
aws s3 cp "s3://${BUCKET_NAME}/${WORKER_BUNDLE_KEY}" "$BUNDLE" || report_fail "worker bundle missing"
echo "${WORKER_BUNDLE_SHA256}  ${BUNDLE}" | sha256sum -c - || report_fail "worker bundle checksum mismatch"
tar -xzf "$BUNDLE" -C "$INSTALL_ROOT"

python3 -m venv "$VENV"
"${VENV}/bin/pip" install --no-cache-dir --upgrade pip
VENDOR="${INSTALL_ROOT}/photopipeline-worker/vendor"
if [[ ! -d "$VENDOR" ]] || [[ -z "$(ls -A "$VENDOR" 2>/dev/null || true)" ]]; then
  report_fail "worker bundle missing vendored wheels (rebuild with scripts/build-worker-bundle.sh)"
fi
"${VENV}/bin/pip" install --no-cache-dir --no-index --find-links="$VENDOR" \
  -r "${INSTALL_ROOT}/photopipeline-worker/requirements.txt"

LC=/tmp/lightcraft-cli
aws s3 cp "s3://${BUCKET_NAME}/${LIGHTCRAFT_BINARY_KEY}" "$LC" || report_fail "lightcraft binary missing"
echo "${LIGHTCRAFT_BINARY_SHA256}  ${LC}" | sha256sum -c - || report_fail "lightcraft checksum mismatch"
chmod +x "$LC"
mkdir -p "${INSTALL_ROOT}/lightcraft"
mv "$LC" "$LIGHTCRAFT_BIN"

cancel_bootstrap_backstop
arm_worker_idle_backstop

cd "${INSTALL_ROOT}/photopipeline-worker"
exec "$WORKER_PY" -m worker.main
