import { computeDeadlineAt, computeMaxRuntimeMinutes } from '../lambda/pricing';

describe('computeMaxRuntimeMinutes', () => {
  it('clamps small and large shoots to 15–120 minutes', () => {
    expect(computeMaxRuntimeMinutes(1)).toBe(15);
    expect(computeMaxRuntimeMinutes(10_000)).toBe(120);
  });

  it('scales with file count using pricing.json per-file render estimate', () => {
    const small = computeMaxRuntimeMinutes(20);
    const large = computeMaxRuntimeMinutes(400);
    expect(large).toBeGreaterThan(small);
    expect(large).toBeLessThanOrEqual(120);
  });
});

describe('computeDeadlineAt', () => {
  it('adds minutes in ms', () => {
    const t0 = 1_700_000_000_000;
    expect(computeDeadlineAt(t0, 30)).toBe(t0 + 30 * 60_000);
  });
});
