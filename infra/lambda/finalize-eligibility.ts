/** When POST /finalize may relaunch the worker (reprocess). */

export type ShootFileRow = { state?: string };

export function countPublishedFiles(files: ShootFileRow[]): number {
  return files.filter((f) => f.state === 'GP_CREATED' || f.state === 'PUBLISHED').length;
}

export function canReprocessShoot(
  meta: {
    state?: string;
    publishedCount?: number;
    totalFiles?: number;
    imageCount?: number;
  },
  files: ShootFileRow[],
  opts: { force?: boolean },
): boolean {
  const state = meta.state ?? '';
  const force = Boolean(opts.force);
  const total = meta.totalFiles ?? files.length ?? meta.imageCount ?? 0;
  const publishedFromFiles = countPublishedFiles(files);
  const published = Math.max(meta.publishedCount ?? 0, publishedFromFiles);

  if (force) {
    return state === 'DONE' || state === 'PARTIAL' || state === 'FAILED';
  }

  if (state === 'PARTIAL' || state === 'FAILED') return true;
  if (state === 'DONE') {
    if (published === 0) return true;
    if (total > 0 && published < total) return true;
  }
  return false;
}
