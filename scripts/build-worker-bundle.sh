#!/usr/bin/env bash
# Build worker + metadata tarball for S3 deploy (not uploaded by CI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/dist}"
BUNDLE="$OUT_DIR/photopipeline-worker.tar.gz"
SHA_FILE="$OUT_DIR/photopipeline-worker.tar.gz.sha256"

mkdir -p "$OUT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/photopipeline-worker"
cp -a "$ROOT/worker/worker" "$TMP/photopipeline-worker/"
cp "$ROOT/worker/requirements.txt" "$TMP/photopipeline-worker/"
tar -C "$TMP" -czf "$BUNDLE" photopipeline-worker
sha256sum "$BUNDLE" | awk '{print $1}' > "$SHA_FILE"
echo "Built $BUNDLE"
echo "SHA256 $(cat "$SHA_FILE")"
