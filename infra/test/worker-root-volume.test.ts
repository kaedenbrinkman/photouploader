import { computeRootVolumeGiB, ebsGp3ShootUsd } from '../lambda/worker-root-volume';

describe('computeRootVolumeGiB', () => {
  it('uses 30 GB minimum for photo-only shoots', () => {
    const files = Array.from({ length: 374 }, (_, i) => ({
      size_bytes: 35_000_000,
      original_name: `IMG_${i}.CR3`,
    }));
    expect(computeRootVolumeGiB(files)).toBe(30);
  });

  it('scales for large video (2× largest + 20 GB working set)', () => {
    const tenGb = 10 * 1024 ** 3;
    expect(
      computeRootVolumeGiB([{ size_bytes: tenGb, original_name: 'clip.MP4' }]),
    ).toBe(40);
  });

  it('caps at 200 GB', () => {
    const huge = 100 * 1024 ** 3;
    expect(computeRootVolumeGiB([{ size_bytes: huge, original_name: 'big.MOV' }])).toBe(200);
  });
});

describe('ebsGp3ShootUsd', () => {
  it('prorates gp3 cost to processing duration', () => {
    const usd = ebsGp3ShootUsd(30, 60, 0.08);
    expect(usd).toBeGreaterThan(0);
    expect(usd).toBeLessThan(0.05);
  });
});
