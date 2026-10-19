export function isPastDeadline(
  deadlineAt: number | undefined,
  nowMs: number,
): boolean {
  if (deadlineAt == null || !Number.isFinite(deadlineAt)) return false;
  return nowMs > deadlineAt;
}

export function runtimeTimeoutError(deadlineAt: number): string {
  return `Worker timed out (auto-stop deadline ${new Date(deadlineAt).toISOString()})`;
}
