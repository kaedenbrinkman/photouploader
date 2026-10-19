#!/usr/bin/env bash
# Build worker + metadata tarball for S3 deploy (not uploaded by CI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/dist}"
BUNDLE="$OUT_DIR/photopipeline-worker.tar.gz"
SHA_FILE="$OUT_DIR/photopipeline-worker.tar.gz.sha256"
TARGET_PY="${TARGET_PY:-3.9}"

mkdir -p "$OUT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/photopipeline-worker"
cp -a "$ROOT/worker/worker" "$TMP/photopipeline-worker/"
cp -a "$ROOT/gphotos" "$TMP/photopipeline-worker/"
cp "$ROOT/worker/requirements.txt" "$TMP/photopipeline-worker/"

# Vendored wheels for AL2023 bootstrap (avoid system pip / RPM conflicts; no PyPI at boot).
VENDOR="$TMP/photopipeline-worker/vendor"
mkdir -p "$VENDOR"

BUILD_VENV="$TMP/build-venv"
CONSTRAINTS="$ROOT/worker/vendor.constraints.txt"

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
    if [[ -z "$py" ]]; then
      uv python install "${TARGET_PY}" >/dev/null
      py="$(uv python find "${TARGET_PY}")"
    fi
    echo "$py"
    return 0
  fi
  return 1
}

create_build_venv() {
  local py
  if ! py="$(find_target_python)"; then
    cat >&2 <<EOF
Python ${TARGET_PY} required to vendor wheels (urllib3/botocore compatibility).
Install python${TARGET_PY}, or install uv (https://docs.astral.sh/uv/) and re-run.
EOF
    exit 1
  fi
  "$py" -m venv "$BUILD_VENV"
}

create_build_venv
PIP="${BUILD_VENV}/bin/pip"
PY="${BUILD_VENV}/bin/python"
"$PIP" install -q --upgrade pip >/dev/null

PY_VER="$("$PY" -c 'import sys; print(".".join(map(str, sys.version_info[:2])))')"
if [[ "$PY_VER" != "$TARGET_PY" ]]; then
  echo "build venv is Python ${PY_VER}, expected ${TARGET_PY}" >&2
  exit 1
fi

download_args=(
  "$PIP" download -q
  -r "$ROOT/worker/requirements.txt"
  -d "$VENDOR"
  --only-binary=:all:
)
if [[ -f "$CONSTRAINTS" ]]; then
  download_args+=(-c "$CONSTRAINTS")
fi

if ! "${download_args[@]}" \
  --platform manylinux2014_x86_64 \
  --platform manylinux_2_17_x86_64; then
  "${download_args[@]}"
fi

if compgen -G "$VENDOR/urllib3-2.*.whl" >/dev/null; then
  echo "refusing to bundle urllib3 2.x for Python ${TARGET_PY} (breaks botocore)" >&2
  exit 1
fi

tar -C "$TMP" --sort=name --mtime=@0 --owner=0 --group=0 -cf - photopipeline-worker | gzip -n > "$BUNDLE"
sha256sum "$BUNDLE" | awk '{print $1}' > "$SHA_FILE"
echo "Built $BUNDLE"
echo "SHA256 $(cat "$SHA_FILE")"
