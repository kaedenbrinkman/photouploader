#!/usr/bin/env bash
# Reproducible worker image bootstrap (Amazon Linux 2023).
# Pin matches feasibility report: LightCraft v0.9.1 @ 31a677fe
set -euo pipefail

LIGHTCRAFT_REPO="${LIGHTCRAFT_REPO:-https://github.com/storytold/lightcraft.git}"
LIGHTCRAFT_COMMIT="${LIGHTCRAFT_COMMIT:-31a677fe3dbf4bc1042f8f6346e83b0afd1b04e8}"
RUST_VERSION="${RUST_VERSION:-1.90.0}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/photopipeline}"
LIGHTCRAFT_BIN="${INSTALL_ROOT}/lightcraft/lightcraft-cli"
SKIP_LIGHTCRAFT_BUILD="${SKIP_LIGHTCRAFT_BUILD:-0}"

log() { echo "[bootstrap] $*"; }

install_prereqs() {
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y git gcc gcc-c++ make python3 python3-pip tar curl
  elif command -v yum >/dev/null 2>&1; then
    yum install -y git gcc gcc-c++ make python3 python3-pip tar curl
  fi
}

install_rust() {
  if [[ -x "${HOME}/.cargo/bin/lightcraft-cli" ]]; then
    return 0
  fi
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain "${RUST_VERSION}"
  # shellcheck disable=SC1091
  source "${HOME}/.cargo/env"
  rustc --version
}

build_lightcraft() {
  if [[ "${SKIP_LIGHTCRAFT_BUILD}" == "1" ]]; then
    log "SKIP_LIGHTCRAFT_BUILD=1 — installing stub CLI"
    mkdir -p "$(dirname "${LIGHTCRAFT_BIN}")"
    cat >"${LIGHTCRAFT_BIN}" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "--version" ]]; then
  echo "lightcraft-cli stub (set SKIP_LIGHTCRAFT_BUILD=0 to compile pinned commit)"
  exit 0
fi
echo "stub lightcraft-cli — build AMI with SKIP_LIGHTCRAFT_BUILD=0" >&2
exit 1
STUB
    chmod +x "${LIGHTCRAFT_BIN}"
    return 0
  fi

  install_rust
  local src="${INSTALL_ROOT}/src/lightcraft"
  rm -rf "${src}"
  git clone "${LIGHTCRAFT_REPO}" "${src}"
  git -C "${src}" checkout "${LIGHTCRAFT_COMMIT}"
  # shellcheck disable=SC1091
  source "${HOME}/.cargo/env"
  cargo build --release -p lightcraft-cli --manifest-path "${src}/Cargo.toml"
  mkdir -p "$(dirname "${LIGHTCRAFT_BIN}")"
  install -m 0755 "${src}/target/release/lightcraft-cli" "${LIGHTCRAFT_BIN}"
  "${LIGHTCRAFT_BIN}" --version || true
}

install_worker() {
  mkdir -p "${INSTALL_ROOT}/worker"
  if [[ -d /tmp/photopipeline-worker ]]; then
    cp -a /tmp/photopipeline-worker/. "${INSTALL_ROOT}/worker/"
  fi
  python3 -m pip install --no-cache-dir -r "${INSTALL_ROOT}/worker/requirements.txt" 2>/dev/null || true
}

main() {
  log "starting bootstrap commit=${LIGHTCRAFT_COMMIT} skip=${SKIP_LIGHTCRAFT_BUILD}"
  install_prereqs
  build_lightcraft
  install_worker
  log "done: ${LIGHTCRAFT_BIN}"
}

main "$@"
