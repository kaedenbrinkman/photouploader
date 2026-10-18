export interface ManifestFile {
  sha256: string;
  size_bytes: number;
  s3_key?: string;
  original_name?: string;
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
  const seen = new Set<string>();
  for (const f of manifest.files) {
    const sha = String(f.sha256).toLowerCase();
    if (sha.length !== 64) throw new ManifestValidationError(`invalid sha256: ${sha}`);
    if (seen.has(sha)) throw new ManifestValidationError(`duplicate sha256: ${sha}`);
    seen.add(sha);
    if (Number(f.size_bytes) <= 0) throw new ManifestValidationError(`invalid size for ${sha}`);
  }
}

export function rawKeyForFile(shootId: string, f: ManifestFile): string {
  const ext = (f.original_name ?? '').toUpperCase().endsWith('.JPG') ? '.JPG' : '.CR3';
  return `shoots/${shootId}/raw/${String(f.sha256).toLowerCase()}${ext}`;
}
