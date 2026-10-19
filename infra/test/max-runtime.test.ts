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

  it('allows enough wall clock for a 362-photo CR3 shoot', () => {
    const mins = computeMaxRuntimeMinutes(362);
    expect(mins).toBeGreaterThanOrEqual(45);
    expect(mins).toBeLessThanOrEqual(120);
  });

  it('adds runtime for large video byte volume', () => {
    const photosOnly = computeMaxRuntimeMinutes(10, 0);
    const withVideo = computeMaxRuntimeMinutes(10, 20 * 1024 ** 3);
    expect(withVideo).toBeGreaterThan(photosOnly);
  });

  it('uses shorter cap for publish-only retries', () => {
    const full = computeMaxRuntimeMinutes(362, 0);
    const publishOnly = computeMaxRuntimeMinutes(362, 0, { publishOnly: true });
    expect(publishOnly).toBeLessThan(full);
    expect(publishOnly).toBeGreaterThanOrEqual(15);
  });
});

describe('computeDeadlineAt', () => {
  it('adds minutes in ms', () => {
    const t0 = 1_700_000_000_000;
    expect(computeDeadlineAt(t0, 30)).toBe(t0 + 30 * 60_000);
  });
});
