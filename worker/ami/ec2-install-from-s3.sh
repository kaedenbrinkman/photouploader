#!/usr/bin/env bash
# Ephemeral worker bootstrap on stock AL2023 (no custom AMI). Invoked from finalize user-data.
set -euo pipefail

INSTALL_ROOT="${INSTALL_ROOT:-/opt/photopipeline}"
BUNDLE_KEY="${WORKER_BUNDLE_KEY:?WORKER_BUNDLE_KEY required}"
BUNDLE_SHA256="${WORKER_BUNDLE_SHA256:?WORKER_BUNDLE_SHA256 required}"
LIGHTCRAFT_KEY="${LIGHTCRAFT_BINARY_KEY:?LIGHTCRAFT_BINARY_KEY required}"
LIGHTCRAFT_SHA256="${LIGHTCRAFT_BINARY_SHA256:?LIGHTCRAFT_BINARY_SHA256 required}"
SHOOT_ID="${SHOOT_ID:?SHOOT_ID required}"
DYNAMODB_TABLE="${DYNAMODB_TABLE:?DYNAMODB_TABLE required}"
BUCKET_NAME="${BUCKET_NAME:?BUCKET_NAME required}"
REGION="${AWS_DEFAULT_REGION:-$(curl -sf http://169.254.169.254/latest/meta-data/placement/region)}"

export AWS_DEFAULT_REGION="$REGION"

log() { echo "[worker-install] $*"; }

report_fail() {
  local msg="$1"
  log "FATAL: $msg"
  REGION=$(curl -sf http://169.254.169.254/latest/meta-data/placement/region || true)
  export AWS_DEFAULT_REGION="${REGION:-us-west-2}"
  export BOOT_MSG="$msg"
  python3 - <<'PY'
import boto3, os, time
region = os.environ.get("AWS_DEFAULT_REGION") or "us-west-2"
ddb = boto3.client("dynamodb", region_name=region)
ddb.update_item(
    TableName=os.environ["DYNAMODB_TABLE"],
    Key={"pk": {"S": f"SHOOT#{os.environ['SHOOT_ID']}"}, "sk": {"S": "META"}},
    UpdateExpression="SET #s = :f, phase = :ph, lastError = :e, updatedAt = :t",
    ExpressionAttributeNames={"#s": "state"},
    ExpressionAttributeValues={
        ":f": {"S": "FAILED"},
        ":ph": {"S": "BOOTSTRAP_FAILED"},
        ":e": {"S": os.environ.get("BOOT_MSG", "bootstrap failed")},
        ":t": {"N": str(int(time.time() * 1000))},
    },
)
PY
  sudo shutdown -h now
  exit 1
}

dnf install -y aws-cli python3 python3-pip tar || yum install -y aws-cli python3 python3-pip tar

mkdir -p "$INSTALL_ROOT"
BUNDLE="/tmp/worker-bundle.tar.gz"
LC_BIN="/tmp/lightcraft-cli"

aws s3 cp "s3://${BUCKET_NAME}/${BUNDLE_KEY}" "$BUNDLE" || report_fail "worker bundle missing s3://${BUCKET_NAME}/${BUNDLE_KEY}"
echo "${BUNDLE_SHA256}  ${BUNDLE}" | sha256sum -c - || report_fail "worker bundle checksum mismatch"

tar -xzf "$BUNDLE" -C "$INSTALL_ROOT"
python3 -m pip install --no-cache-dir -r "${INSTALL_ROOT}/photopipeline-worker/requirements.txt"

aws s3 cp "s3://${BUCKET_NAME}/${LIGHTCRAFT_KEY}" "$LC_BIN" || report_fail "lightcraft binary missing s3://${BUCKET_NAME}/${LIGHTCRAFT_KEY}"
echo "${LIGHTCRAFT_SHA256}  ${LC_BIN}" | sha256sum -c - || report_fail "lightcraft binary checksum mismatch"
chmod +x "$LC_BIN"
mkdir -p "${INSTALL_ROOT}/lightcraft"
mv "$LC_BIN" "${INSTALL_ROOT}/lightcraft/lightcraft-cli"

log "bootstrap complete"
