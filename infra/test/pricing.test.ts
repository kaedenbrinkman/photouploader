import { actualComputeCost, estimateShoot } from '../lambda/pricing';

describe('estimateShoot', () => {
  it('scales compute with photo count', () => {
    const small = estimateShoot(10, 1e9);
    const large = estimateShoot(500, 30e9);
    expect(large.computeUsd).toBeGreaterThan(small.computeUsd);
    expect(large.processingMinutes).toBeGreaterThan(small.processingMinutes);
  });

  it('video-only shoots have minimal compute', () => {
    const photos = estimateShoot(50, 1e9);
    const videosOnly = estimateShoot(0, 5e9, { videoCount: 5 });
    expect(videosOnly.computeUsd).toBeLessThan(photos.computeUsd);
    expect(videosOnly.imageCount).toBe(5);
  });

  it('includes transfer acceleration surcharge when requested', () => {
    const bytes = 10e9;
    const base = estimateShoot(100, bytes);
    const accel = estimateShoot(100, bytes, { transferAcceleration: true });
    expect(accel.transferAccelerationUsd).toBeGreaterThan(0);
    expect(accel.totalShootUsd).toBeGreaterThan(base.totalShootUsd);
  });
});

describe('actualComputeCost', () => {
  it('returns positive USD for elapsed seconds', () => {
    expect(actualComputeCost(3600, 'c7i.2xlarge', true)).toBeGreaterThan(0);
  });
});
