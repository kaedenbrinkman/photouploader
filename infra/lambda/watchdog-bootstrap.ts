/** Shoot phases where bootstrap/instance bring-up should not exceed a fixed wall clock. */
export const BOOTSTRAP_STUCK_PHASES = ['INSTANCE_STARTING', 'BOOTSTRAPPING'] as const;

export type BootstrapStuckPhase = (typeof BOOTSTRAP_STUCK_PHASES)[number];

export function bootstrapStuckTimeoutMs(timeoutMinutes: number): number {
  const m = Number.isFinite(timeoutMinutes) && timeoutMinutes > 0 ? timeoutMinutes : 10;
  return m * 60_000;
}

export function isBootstrapStuck(
  phase: string | undefined,
  workerStartedAt: number | undefined,
  updatedAt: number | undefined,
  nowMs: number,
  timeoutMs: number,
): boolean {
  if (!phase || !BOOTSTRAP_STUCK_PHASES.includes(phase as BootstrapStuckPhase)) {
    return false;
  }
  const anchor =
    phase === 'BOOTSTRAPPING'
      ? (updatedAt ?? workerStartedAt ?? 0)
      : (workerStartedAt ?? updatedAt ?? 0);
  if (anchor <= 0) return false;
  return nowMs - anchor > timeoutMs;
}
