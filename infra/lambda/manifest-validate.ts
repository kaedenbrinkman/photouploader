import { rawExtensionForName } from './media-kind';

export interface ManifestFile {
  sha256: string;
  size_bytes: number;
  s3_key?: string;
  original_name?: string;
  content_type?: string;
  storage_id?: number;
  object_handle?: number;
  capture_time_epoch_ms?: number;
}

export interface ShootManifest {
  shoot_id: string;
  user_id: string;
  album_title?: string;
  settings_hash?: string;
  files: ManifestFile[];
}

export class ManifestValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ManifestValidationError';
  }
}

const RAW_PREFIX = (shootId: string) => `shoots/${shootId}/raw/`;

export function validateManifestS3Key(shootId: string, key: string): void {
  const prefix = RAW_PREFIX(shootId);
  if (!key.startsWith(prefix)) {
    throw new ManifestValidationError(`invalid s3_key prefix for shoot ${shootId}: ${key}`);
  }
  if (key.includes('..')) {
    throw new ManifestValidationError(`invalid s3_key path: ${key}`);
  }
}

export function validateManifest(manifest: ShootManifest, expectedShootId: string, expectedUserId: string): void {
  if (manifest.shoot_id !== expectedShootId) {
    throw new ManifestValidationError('shoot_id mismatch');
  }
  if (manifest.user_id !== expectedUserId) {
    throw new ManifestValidationError('user_id mismatch');
  }
  if (!manifest.files?.length) {
    throw new ManifestValidationError('manifest must contain files');
  }
  const seenSha = new Set<string>();
  const seenKeys = new Set<string>();
  for (const f of manifest.files) {
    const sha = String(f.sha256).toLowerCase();
    if (sha.length !== 64) throw new ManifestValidationError(`invalid sha256: ${sha}`);
    if (seenSha.has(sha)) throw new ManifestValidationError(`duplicate sha256: ${sha}`);
    seenSha.add(sha);
    if (Number(f.size_bytes) <= 0) throw new ManifestValidationError(`invalid size for ${sha}`);
    if (f.s3_key) {
      validateManifestS3Key(expectedShootId, f.s3_key);
      if (seenKeys.has(f.s3_key)) throw new ManifestValidationError(`duplicate s3_key: ${f.s3_key}`);
      seenKeys.add(f.s3_key);
    }
  }
}

/** S3 object key for a manifest file (honors manifest s3_key when set). */
/** 64-char upload-ref hex from shoots/{id}/raw/{ref}.ext */
export function uploadRefFromRawS3Key(key: string): string | null {
  const m = key.match(/\/raw\/([a-f0-9]{64})\./i);
  return m ? m[1]!.toLowerCase() : null;
}

export function rawKeyForFile(shootId: string, f: ManifestFile): string {
  if (f.s3_key) {
    validateManifestS3Key(shootId, f.s3_key);
    return f.s3_key;
  }
  const ext = rawExtensionForName(f.original_name);
  return `${RAW_PREFIX(shootId)}${String(f.sha256).toLowerCase()}${ext}`;
}

export function findMissingManifestObjects(
  shootId: string,
  files: ManifestFile[],
  headContentLength: (key: string) => number | null | undefined,
): string[] {
  const missing: string[] = [];
  for (const f of files) {
    const key = rawKeyForFile(shootId, f);
    const size = headContentLength(key);
    if (size == null || Number(size) !== Number(f.size_bytes)) missing.push(key);
  }
  return missing;
}
