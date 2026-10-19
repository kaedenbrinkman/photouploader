import type { ManifestFile } from './manifest-validate';
import { rawKeyForFile } from './manifest-validate';

export const SPOT_CHECK_MAX_FILES = 20;
export const SPOT_CHECK_CONCURRENCY = 20;

export interface DynamoManifestFileRow {
  sha256?: string;
  state?: string;
  sizeBytes?: number;
}

/** Spread up to [maxSamples] indices across [fileCount] for S3 spot-check. */
export function pickSpotCheckIndices(fileCount: number, maxSamples = SPOT_CHECK_MAX_FILES): number[] {
  if (fileCount <= 0) return [];
  if (fileCount <= maxSamples) return [...Array(fileCount).keys()];
  const indices: number[] = [];
  for (let i = 0; i < maxSamples; i++) {
    indices.push(Math.floor((i * fileCount) / maxSamples));
  }
  return indices;
}

/** Trust DynamoDB FILE# rows: every manifest entry must be UPLOADED with matching size. */
export function manifestKeysMissingFromDynamo(
  shootId: string,
  files: ManifestFile[],
  rows: DynamoManifestFileRow[],
): string[] {
  const bySha = new Map<string, { state?: string; sizeBytes?: number }>();
  for (const row of rows) {
    const sha = String(row.sha256 ?? '').toLowerCase();
    if (sha.length === 64) bySha.set(sha, { state: row.state, sizeBytes: row.sizeBytes });
  }
  const missing: string[] = [];
  for (const f of files) {
    const sha = String(f.sha256).toLowerCase();
    const row = bySha.get(sha);
    const key = rawKeyForFile(shootId, f);
    if (!row || row.state !== 'UPLOADED') {
      missing.push(key);
      continue;
    }
    if (Number(row.sizeBytes) !== Number(f.size_bytes)) missing.push(key);
  }
  return missing;
}

export async function spotCheckManifestOnS3(
  files: ManifestFile[],
  shootId: string,
  headContentLength: (key: string) => Promise<number | null | undefined>,
  options?: { maxSamples?: number; concurrency?: number },
): Promise<string[]> {
  const maxSamples = options?.maxSamples ?? SPOT_CHECK_MAX_FILES;
  const concurrency = options?.concurrency ?? SPOT_CHECK_CONCURRENCY;
  const indices = pickSpotCheckIndices(files.length, maxSamples);
  const toCheck = indices.map((i) => files[i]);
  const missing: string[] = [];

  await mapPool(toCheck, concurrency, async (f) => {
    const key = rawKeyForFile(shootId, f);
    const len = await headContentLength(key);
    if (len == null || Number(len) !== Number(f.size_bytes)) missing.push(key);
  });

  return missing;
}

async function mapPool<T>(items: T[], concurrency: number, fn: (item: T) => Promise<void>): Promise<void> {
  if (!items.length) return;
  let next = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (true) {
      const idx = next++;
      if (idx >= items.length) break;
      await fn(items[idx]!);
    }
  });
  await Promise.all(workers);
}
