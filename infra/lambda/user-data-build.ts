/** EC2 user-data assembly (LF-only; safe for bash on AL2023). */

export interface UserDataBuildParams {
  shootId: string;
  userId: string;
  bucket: string;
  table: string;
  tokenParam: string;
  settingsHash: string;
  albumTitle: string;
  bundleKey: string;
  bundleSha256: string;
  lightcraftKey: string;
  lightcraftSha256: string;
  maxRuntimeMinutes: number;
}

/** Bash user-data must be LF-only (CRLF from Windows checkouts breaks `set -o pipefail`). */
export function normalizeLinuxLineEndings(text: string): string {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
}

export function buildUserData(params: UserDataBuildParams, bootstrapScript: string): string {
  const {
    shootId,
    userId,
    bucket,
    table,
    tokenParam,
    settingsHash,
    albumTitle,
    bundleKey,
    bundleSha256,
    lightcraftKey,
    lightcraftSha256,
    maxRuntimeMinutes,
  } = params;
  const exports = `#!/bin/bash
sudo shutdown -h +${maxRuntimeMinutes} "photopipeline shoot runtime cap" 2>/dev/null || true
export MAX_RUNTIME_MINUTES=${maxRuntimeMinutes}
export SHOOT_ID=${shootId}
export USER_ID=${userId}
export BUCKET_NAME=${bucket}
export DYNAMODB_TABLE=${table}
export GPHOTOS_TOKEN_PARAM=${tokenParam}
export SETTINGS_HASH=${settingsHash}
export ALBUM_TITLE=${JSON.stringify(albumTitle)}
export WORKER_BUNDLE_KEY=${bundleKey}
export WORKER_BUNDLE_SHA256=${bundleSha256}
export LIGHTCRAFT_BINARY_KEY=${lightcraftKey}
export LIGHTCRAFT_BINARY_SHA256=${lightcraftSha256}
export LIGHTCRAFT_BIN=/opt/photopipeline/lightcraft/lightcraft-cli
export INSTALL_ROOT=/opt/photopipeline
`;
  const bootstrap = normalizeLinuxLineEndings(bootstrapScript);
  return normalizeLinuxLineEndings(`${exports}${bootstrap}`);
}
