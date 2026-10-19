import {
  metaTerminalWorkerUpdate,
  persistedActualCompute,
  snapshotActualComputeFields,
} from '../lambda/shoot-finalize-meta';

describe('snapshotActualComputeFields', () => {
  it('persists seconds, usd, and finalInstanceType', () => {
    const snap = snapshotActualComputeFields(
      {
        workerStartedAt: 1_000,
        instanceType: 't3a.medium',
        spot: true,
      },
      901_000,
    );
    expect(snap.workerFinishedAt).toBe(901_000);
    expect(snap.actualComputeSeconds).toBe(900);
    expect(snap.actualComputeUsd).toBeGreaterThan(0);
    expect(snap.finalInstanceType).toBe('t3a.medium');
  });
});

describe('persistedActualCompute', () => {
  it('reads stored META fields', () => {
    expect(
      persistedActualCompute({ actualComputeSeconds: 120, actualComputeUsd: 0.01 }),
    ).toEqual({ seconds: 120, usd: 0.01 });
  });
});

describe('metaTerminalWorkerUpdate', () => {
  it('REMOVEs deadlineAt with worker ephemerals', () => {
    const u = metaTerminalWorkerUpdate(
      { workerStartedAt: 0, instanceType: 'c7i.2xlarge', spot: false },
      3_600_000,
      { state: 'FAILED', phase: 'RUNTIME_TIMEOUT', lastError: 'timeout' },
    );
    expect(u.UpdateExpression).toContain('REMOVE');
    expect(u.UpdateExpression).toContain('deadlineAt');
  });
});
