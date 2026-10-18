#!/usr/bin/env bash
# Ephemeral worker bootstrap on stock AL2023 (no custom AMI). Used by CI and local tests;
# production EC2 user-data is built from the same ec2-userdata-bootstrap.sh in finalize Lambda.
set -euo pipefail

INSTALL_ROOT="${INSTALL_ROOT:-/opt/photopipeline}"
: "${WORKER_BUNDLE_KEY:?WORKER_BUNDLE_KEY required}"
: "${WORKER_BUNDLE_SHA256:?WORKER_BUNDLE_SHA256 required}"
: "${LIGHTCRAFT_BINARY_KEY:?LIGHTCRAFT_BINARY_KEY required}"
: "${LIGHTCRAFT_BINARY_SHA256:?LIGHTCRAFT_BINARY_SHA256 required}"
: "${SHOOT_ID:?SHOOT_ID required}"
: "${DYNAMODB_TABLE:?DYNAMODB_TABLE required}"
: "${BUCKET_NAME:?BUCKET_NAME required}"

REGION="${AWS_DEFAULT_REGION:-$(curl -sf http://169.254.169.254/latest/meta-data/placement/region || true)}"
export AWS_DEFAULT_REGION="${REGION:-us-west-2}"
export INSTALL_ROOT

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../../infra/lambda/ec2-userdata-bootstrap.sh
source "${ROOT}/infra/lambda/ec2-userdata-bootstrap.sh"
