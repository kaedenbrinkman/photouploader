import {
  albumOpenUrl,
  mergeProgress,
  progressCountsFromFiles,
  runningActualCompute,
} from '../lambda/status-progress';

describe('progressCountsFromFiles', () => {
  it('counts rendered and published from file states', () => {
    const files = [
      { state: 'GP_CREATED' },
      { state: 'RENDERED' },
      { state: 'UPLOADED' },
    ];
    expect(progressCountsFromFiles(files)).toEqual({ renderedCount: 2, publishedCount: 1 });
  });
});

describe('mergeProgress', () => {
  it('derives counts from per-file states capped at total', () => {
    const files = [{ state: 'RENDERED' }, { state: 'RENDERED' }];
    const merged = mergeProgress(files, { totalFiles: 13, renderedCount: 0, publishedCount: 0 });
    expect(merged.renderedCount).toBe(2);
    expect(merged.totalFiles).toBe(13);
    expect(merged.uploadCompleteCount).toBe(0);
  });

  it('ignores inflated meta renderedCount when file rows exist', () => {
    const files = Array.from({ length: 13 }, () => ({ state: 'RENDERED' }));
    const merged = mergeProgress(files, { totalFiles: 13, renderedCount: 26, publishedCount: 99 });
    expect(merged.renderedCount).toBe(13);
    expect(merged.publishedCount).toBe(0);
  });
});

describe('runningActualCompute', () => {
  const compute = (sec: number) => sec * 0.01;

  it('returns running cost until worker finishes', () => {
    const started = 1_000_000;
    const now = started + 90_000;
    const r = runningActualCompute(started, undefined, 'c7i.2xlarge', true, now, compute);
    expect(r.seconds).toBe(90);
    expect(r.usd).toBeCloseTo(0.9, 5);
  });

  it('is empty without workerStartedAt', () => {
    expect(runningActualCompute(undefined, undefined, 'c7i.2xlarge', true, Date.now(), compute)).toEqual({});
  });
});

describe('albumOpenUrl', () => {
  it('prefers productUrl', () => {
    expect(albumOpenUrl('id', 'https://photos.app.goo.gl/abc')).toBe('https://photos.app.goo.gl/abc');
  });

  it('falls back to lr album link', () => {
    expect(albumOpenUrl('album123', undefined)).toBe('https://photos.google.com/lr/album/album123');
  });
});
