import { actualComputeCost, estimateShoot } from '../lambda/pricing';

describe('estimateShoot', () => {
  it('increases with image count', () => {
    const small = estimateShoot(10, 1e9);
    const large = estimateShoot(500, 30e9);
    expect(large.computeUsd).toBeGreaterThan(small.computeUsd);
    expect(large.processingMinutes).toBeGreaterThan(small.processingMinutes);
  });

  it('actual compute cost is positive', () => {
    expect(actualComputeCost(600, 'c7i.2xlarge', true)).toBeGreaterThan(0);
  });

  it('adds transfer acceleration surcharge when enabled', () => {
    const bytes = 10 * 1024 ** 3;
    const base = estimateShoot(100, bytes);
    const accel = estimateShoot(100, bytes, { transferAcceleration: true });
    expect(accel.transferAccelerationUsd).toBeCloseTo(0.4, 4);
    expect(accel.totalShootUsd).toBeCloseTo(base.totalShootUsd + 0.4, 4);
  });
});
