/** Progress and compute helpers for GET /shoots/{id}/status (unit-tested). */

export type StatusFileRow = { state?: string; mediaType?: string };

const PUBLISHED_STATES = new Set(['GP_CREATED', 'PUBLISHED']);

function isVideoRow(f: StatusFileRow): boolean {
  return String(f.mediaType ?? '').toLowerCase() === 'video';
}

export function progressCountsFromFiles(files: StatusFileRow[]): {
  renderedCount: number;
  publishedCount: number;
  photoCount: number;
  videoCount: number;
  renderedPhotoCount: number;
  publishedPhotoCount: number;
  publishedVideoCount: number;
} {
  let renderedCount = 0;
  let publishedCount = 0;
  let photoCount = 0;
  let videoCount = 0;
  let renderedPhotoCount = 0;
  let publishedPhotoCount = 0;
  let publishedVideoCount = 0;
  for (const f of files) {
    const st = String(f.state ?? '');
    const video = isVideoRow(f);
    if (video) {
      videoCount += 1;
    } else {
      photoCount += 1;
    }
    const published = PUBLISHED_STATES.has(st);
    if (published) {
      publishedCount += 1;
      if (video) {
        publishedVideoCount += 1;
      } else {
        publishedPhotoCount += 1;
      }
    }
    if (st === 'RENDERED' || (st === 'GP_CREATED' && !video)) {
      renderedCount += 1;
      if (!video) renderedPhotoCount += 1;
    }
  }
  return {
    renderedCount,
    publishedCount,
    photoCount,
    videoCount,
    renderedPhotoCount,
    publishedPhotoCount,
    publishedVideoCount,
  };
}

export function mergeProgress(
  files: StatusFileRow[],
  meta: {
    totalFiles?: number;
    renderedCount?: number;
    publishedCount?: number;
    photoCount?: number;
    videoCount?: number;
    renderedPhotoCount?: number;
    publishedPhotoCount?: number;
    publishedVideoCount?: number;
  },
): {
  totalFiles: number;
  renderedCount: number;
  publishedCount: number;
  uploadCompleteCount: number;
  photoCount: number;
  videoCount: number;
  renderedPhotoCount: number;
  publishedPhotoCount: number;
  publishedVideoCount: number;
} {
  const fromFiles = progressCountsFromFiles(files);
  const totalFiles = Math.max(meta.totalFiles ?? 0, files.length) || files.length;
  const cap = (n: number) => (totalFiles > 0 ? Math.min(totalFiles, n) : n);
  const photoCount = files.length > 0 ? fromFiles.photoCount : Math.max(0, meta.photoCount ?? 0);
  const videoCount = files.length > 0 ? fromFiles.videoCount : Math.max(0, meta.videoCount ?? 0);
  const renderedCount =
    files.length > 0 ? cap(fromFiles.renderedCount) : cap(meta.renderedCount ?? 0);
  const publishedCount =
    files.length > 0 ? cap(fromFiles.publishedCount) : cap(meta.publishedCount ?? 0);
  const renderedPhotoCount =
    files.length > 0
      ? Math.min(photoCount, fromFiles.renderedPhotoCount)
      : Math.min(photoCount, meta.renderedPhotoCount ?? 0);
  const publishedPhotoCount =
    files.length > 0
      ? Math.min(photoCount, fromFiles.publishedPhotoCount)
      : Math.min(photoCount, meta.publishedPhotoCount ?? 0);
  const publishedVideoCount =
    files.length > 0
      ? Math.min(videoCount, fromFiles.publishedVideoCount)
      : Math.min(videoCount, meta.publishedVideoCount ?? 0);
  const uploadCompleteCount = files.filter(
    (f) =>
      f.state === 'UPLOADED' ||
      f.state === 'GP_CREATED' ||
      f.state === 'PUBLISHED',
  ).length;
  return {
    totalFiles,
    renderedCount,
    publishedCount,
    uploadCompleteCount,
    photoCount,
    videoCount,
    renderedPhotoCount,
    publishedPhotoCount,
    publishedVideoCount,
  };
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
