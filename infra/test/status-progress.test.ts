import {
  albumOpenUrl,
  mergeProgress,
  progressCountsFromFiles,
  runningActualCompute,
} from '../lambda/status-progress';

describe('progressCountsFromFiles', () => {
  it('counts rendered photos separately from published videos', () => {
    const files = [
      { state: 'GP_CREATED', mediaType: 'photo' },
      { state: 'RENDERED', mediaType: 'photo' },
      { state: 'PUBLISHED', mediaType: 'video' },
      { state: 'UPLOADED', mediaType: 'video' },
    ];
    expect(progressCountsFromFiles(files)).toEqual({
      renderedCount: 2,
      publishedCount: 2,
      photoCount: 2,
      videoCount: 2,
      renderedPhotoCount: 2,
      publishedPhotoCount: 1,
      publishedVideoCount: 1,
    });
  });

  it('ignores stateless placeholder FILE rows', () => {
    const files = [
      { state: 'GP_CREATED', mediaType: 'photo' },
      { mediaType: 'photo' },
      { state: '', mediaType: 'photo' },
      ...Array.from({ length: 370 }, () => ({ state: undefined, mediaType: 'photo' as const })),
    ];
    const counts = progressCountsFromFiles(files);
    expect(counts.photoCount).toBe(1);
    expect(counts.publishedPhotoCount).toBe(1);
  });
});

describe('mergeProgress', () => {
  it('derives counts from per-file states capped at totals', () => {
    const files = [{ state: 'RENDERED', mediaType: 'photo' }, { state: 'RENDERED', mediaType: 'photo' }];
    const merged = mergeProgress(files, { totalFiles: 13, renderedCount: 0, publishedCount: 0 });
    expect(merged.renderedCount).toBe(2);
    expect(merged.totalFiles).toBe(2);
    expect(merged.uploadCompleteCount).toBe(0);
    expect(merged.photoCount).toBe(2);
  });

  it('dedupes duplicate FILE rows by contentSha256', () => {
    const content = 'd'.repeat(64);
    const files = [
      { state: 'RENDERED', mediaType: 'photo', contentSha256: content },
      { state: 'UPLOADED', mediaType: 'photo', contentSha256: content },
      { state: 'RENDERED', mediaType: 'photo', contentSha256: content },
    ];
    const merged = mergeProgress(files, { totalFiles: 735, photoCount: 362, videoCount: 12 });
    expect(merged.photoCount).toBe(362);
    expect(merged.totalFiles).toBe(374);
    expect(merged.renderedPhotoCount).toBe(1);
  });

  it('prefers META photoCount over inflated stateless FILE rows', () => {
    const files = [
      { state: 'RENDERED', mediaType: 'photo' },
      ...Array.from({ length: 370 }, () => ({ mediaType: 'photo' as const })),
    ];
    const merged = mergeProgress(files, {
      totalFiles: 374,
      photoCount: 362,
      videoCount: 12,
      renderedPhotoCount: 40,
    });
    expect(merged.photoCount).toBe(362);
    expect(merged.videoCount).toBe(12);
    expect(merged.totalFiles).toBe(374);
    expect(merged.renderedPhotoCount).toBe(1);
  });

  it('ignores inflated meta renderedCount when file rows exist', () => {
    const files = Array.from({ length: 13 }, () => ({ state: 'RENDERED', mediaType: 'photo' }));
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
