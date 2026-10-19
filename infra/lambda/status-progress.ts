/** Progress and compute helpers for GET /shoots/{id}/status (unit-tested). */

export type StatusFileRow = {
  state?: string;
  mediaType?: string;
  contentSha256?: string;
};

const PUBLISHED_STATES = new Set(['GP_CREATED', 'PUBLISHED']);

function isVideoRow(f: StatusFileRow): boolean {
  return String(f.mediaType ?? '').toLowerCase() === 'video';
}

export function fileRowHasState(f: StatusFileRow): boolean {
  const st = f.state;
  if (st == null) return false;
  return String(st).trim().length > 0;
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
  const seenPhotoContent = new Set<string>();
  const seenVideoContent = new Set<string>();
  for (const f of files) {
    if (!fileRowHasState(f)) continue;
    const st = String(f.state ?? '');
    const video = isVideoRow(f);
    const contentId = String(f.contentSha256 ?? '').toLowerCase();
    if (video) {
      if (contentId) {
        if (seenVideoContent.has(contentId)) continue;
        seenVideoContent.add(contentId);
      }
      videoCount += 1;
    } else {
      if (contentId) {
        if (seenPhotoContent.has(contentId)) continue;
        seenPhotoContent.add(contentId);
      }
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
  const activeFiles = files.filter(fileRowHasState);
  const fromFiles = progressCountsFromFiles(activeFiles);
  const metaTotal = meta.totalFiles ?? 0;
  const metaPhotos = meta.photoCount ?? 0;
  const metaVideos = meta.videoCount ?? 0;
  const photoCount = metaPhotos > 0 ? metaPhotos : fromFiles.photoCount;
  const videoCount = metaVideos > 0 ? metaVideos : fromFiles.videoCount;
  const totalFiles =
    photoCount + videoCount > 0
      ? photoCount + videoCount
      : metaTotal > 0
        ? metaTotal
        : Math.max(fromFiles.photoCount + fromFiles.videoCount, activeFiles.length);
  const cap = (n: number) => (totalFiles > 0 ? Math.min(totalFiles, n) : n);
  const renderedCount =
    activeFiles.length > 0 ? cap(fromFiles.renderedCount) : cap(meta.renderedCount ?? 0);
  const publishedCount =
    activeFiles.length > 0 ? cap(fromFiles.publishedCount) : cap(meta.publishedCount ?? 0);
  const renderedPhotoCount =
    activeFiles.length > 0
      ? Math.min(photoCount, fromFiles.renderedPhotoCount)
      : Math.min(photoCount, meta.renderedPhotoCount ?? 0);
  const publishedPhotoCount =
    activeFiles.length > 0
      ? Math.min(photoCount, fromFiles.publishedPhotoCount)
      : Math.min(photoCount, meta.publishedPhotoCount ?? 0);
  const publishedVideoCount =
    activeFiles.length > 0
      ? Math.min(videoCount, fromFiles.publishedVideoCount)
      : Math.min(videoCount, meta.publishedVideoCount ?? 0);
  const uploadCompleteCount = activeFiles.filter(
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
