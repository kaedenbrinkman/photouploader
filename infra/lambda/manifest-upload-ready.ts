import type { ManifestFile } from './manifest-validate';
import { rawKeyForFile, uploadRefFromRawS3Key } from './manifest-validate';

export const SPOT_CHECK_MAX_FILES = 20;
export const SPOT_CHECK_CONCURRENCY = 20;

export interface DynamoManifestFileRow {
  /** Lowercase hex from sk FILE#… */
  fileKey?: string;
  sha256?: string;
  state?: string;
  sizeBytes?: number;
}

export interface DynamoFileRowIndex {
  byFileKey: Map<string, DynamoManifestFileRow>;
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

function isUploadedRow(row: DynamoManifestFileRow | undefined, expectedSize: number): boolean {
  if (!row?.state) return false;
  if (row.state !== 'UPLOADED') return false;
  return Number(row.sizeBytes) === Number(expectedSize);
}

function rowScore(row: DynamoManifestFileRow | undefined): number {
  if (!row) return 0;
  if (row.state === 'UPLOADED') return 3;
  if (row.state) return 2;
  return 1;
}

function mergeRow(
  map: Map<string, DynamoManifestFileRow>,
  key: string,
  row: DynamoManifestFileRow,
): void {
  const k = key.toLowerCase();
  if (k.length !== 64) return;
  const existing = map.get(k);
  if (!existing || rowScore(row) > rowScore(existing)) {
    map.set(k, row);
  }
}

/** Index FILE# rows by sk and sha256 attribute (ignores duplicate weaker rows). */
export function indexDynamoFileRows(rows: DynamoManifestFileRow[]): DynamoFileRowIndex {
  const byFileKey = new Map<string, DynamoManifestFileRow>();
  for (const row of rows) {
    if (row.fileKey) mergeRow(byFileKey, row.fileKey, row);
    if (row.sha256) mergeRow(byFileKey, row.sha256, row);
  }
  return { byFileKey };
}

export function uploadRefForManifestFile(shootId: string, f: ManifestFile): string | null {
  try {
    const key = rawKeyForFile(shootId, f);
    return uploadRefFromRawS3Key(key);
  } catch {
    return null;
  }
}

export function resolveManifestFileRow(
  shootId: string,
  f: ManifestFile,
  index: DynamoFileRowIndex,
): DynamoManifestFileRow | undefined {
  const contentSha = String(f.sha256).toLowerCase();
  const uploadRef = uploadRefForManifestFile(shootId, f);
  const keys = uploadRef && uploadRef !== contentSha ? [uploadRef, contentSha] : [uploadRef ?? contentSha];
  for (const key of keys) {
    const row = index.byFileKey.get(key);
    if (isUploadedRow(row, f.size_bytes)) return row;
  }
  return undefined;
}

/** Trust DynamoDB FILE# rows keyed by upload-ref (s3_key) with content-sha fallback. */
export function manifestKeysMissingFromDynamo(
  shootId: string,
  files: ManifestFile[],
  rows: DynamoManifestFileRow[],
): string[] {
  const index = indexDynamoFileRows(rows);
  const missing: string[] = [];
  for (const f of files) {
    const key = rawKeyForFile(shootId, f);
    if (!resolveManifestFileRow(shootId, f, index)) missing.push(key);
  }
  return missing;
}

/** FILE# rows with no state (legacy placeholders) — safe to delete before launch. */
export function statelessPlaceholderFileKeys(rows: DynamoManifestFileRow[]): string[] {
  const keys = new Set<string>();
  for (const row of rows) {
    if (row.state) continue;
    if (row.fileKey && row.fileKey.length === 64) keys.add(row.fileKey);
  }
  return [...keys];
}

export async function filterS3KeysMissing(
  keys: string[],
  expectedSizeByKey: Map<string, number>,
  headContentLength: (key: string) => Promise<number | null | undefined>,
): Promise<string[]> {
  const missing: string[] = [];
  for (const key of keys) {
    const len = await headContentLength(key);
    const expected = expectedSizeByKey.get(key);
    if (len == null || expected == null || Number(len) !== Number(expected)) missing.push(key);
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
  const toCheck = indices.map((i) => files[i]!);
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
