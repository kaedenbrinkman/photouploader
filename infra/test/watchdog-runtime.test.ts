import { isPastDeadline, runtimeTimeoutError } from '../lambda/watchdog-runtime';

describe('watchdog runtime deadline', () => {
  it('detects past deadline', () => {
    expect(isPastDeadline(undefined, 100)).toBe(false);
    expect(isPastDeadline(200, 100)).toBe(false);
    expect(isPastDeadline(50, 100)).toBe(true);
  });

  it('formats timeout error', () => {
    expect(runtimeTimeoutError(0)).toContain('timed out');
  });
});
