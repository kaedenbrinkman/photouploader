/** Progress and compute helpers for GET /shoots/{id}/status (unit-tested). */

export type StatusFileRow = { state?: string };

const RENDERED_STATES = new Set(['RENDERED', 'GP_CREATED']);
const PUBLISHED_STATES = new Set(['GP_CREATED']);

export function progressCountsFromFiles(files: StatusFileRow[]): {
  renderedCount: number;
  publishedCount: number;
} {
  let renderedCount = 0;
  let publishedCount = 0;
  for (const f of files) {
    const st = String(f.state ?? '');
    if (RENDERED_STATES.has(st)) renderedCount += 1;
    if (PUBLISHED_STATES.has(st)) publishedCount += 1;
  }
  return { renderedCount, publishedCount };
}

export function mergeProgress(
  files: StatusFileRow[],
  meta: {
    totalFiles?: number;
    renderedCount?: number;
    publishedCount?: number;
  },
): {
  totalFiles: number;
  renderedCount: number;
  publishedCount: number;
  uploadCompleteCount: number;
} {
  const fromFiles = progressCountsFromFiles(files);
  const totalFiles = meta.totalFiles ?? files.length;
  const renderedCount = Math.max(meta.renderedCount ?? 0, fromFiles.renderedCount);
  const publishedCount = Math.max(meta.publishedCount ?? 0, fromFiles.publishedCount);
  const uploadCompleteCount = files.filter(
    (f) => f.state === 'UPLOADED' || f.state === 'GP_CREATED',
  ).length;
  return { totalFiles, renderedCount, publishedCount, uploadCompleteCount };
}

export function runningActualCompute(
  workerStartedAt: number | undefined,
  workerFinishedAt: number | undefined,
  instanceType: string | undefined,
  spot: boolean | undefined,
  nowMs: number,
  computeFn: (seconds: number, instanceType: string, spot: boolean) => number,
): { seconds?: number; usd?: number } {
  if (!workerStartedAt || !instanceType) return {};
  const endMs = workerFinishedAt ?? nowMs;
  const seconds = Math.max(0, (endMs - workerStartedAt) / 1000);
  const usd = computeFn(seconds, instanceType, Boolean(spot));
  return {
    seconds: Math.round(seconds),
    usd: Number.isFinite(usd) ? usd : 0,
  };
}

export function albumOpenUrl(albumId: string | undefined, productUrl: string | undefined): string | null {
  if (productUrl && productUrl.startsWith('http')) return productUrl;
  if (albumId) return `https://photos.google.com/lr/album/${albumId}`;
  return null;
}
