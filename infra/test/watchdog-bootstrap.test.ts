import {
  bootstrapStuckTimeoutMs,
  isBootstrapStuck,
} from '../lambda/watchdog-bootstrap';

describe('watchdog bootstrap stuck detection', () => {
  const timeoutMs = bootstrapStuckTimeoutMs(10);
  const now = 1_000_000_000_000;

  it('detects INSTANCE_STARTING past timeout from workerStartedAt', () => {
    expect(
      isBootstrapStuck('INSTANCE_STARTING', now - timeoutMs - 1, undefined, now, timeoutMs),
    ).toBe(true);
  });

  it('detects BOOTSTRAPPING past timeout from updatedAt', () => {
    expect(
      isBootstrapStuck('BOOTSTRAPPING', undefined, now - timeoutMs - 5, now, timeoutMs),
    ).toBe(true);
    expect(
      isBootstrapStuck(
        'BOOTSTRAPPING',
        now - 60_000,
        now - timeoutMs - 5,
        now,
        timeoutMs,
      ),
    ).toBe(true);
    expect(
      isBootstrapStuck('BOOTSTRAPPING', now - 60_000, now - 60_000, now, timeoutMs),
    ).toBe(false);
  });

  it('ignores other phases and fresh anchors', () => {
    expect(isBootstrapStuck('RENDERING', now - timeoutMs - 1, undefined, now, timeoutMs)).toBe(
      false,
    );
    expect(isBootstrapStuck('INSTANCE_STARTING', now - 60_000, undefined, now, timeoutMs)).toBe(
      false,
    );
  });
});
