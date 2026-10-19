import { actualComputeCost } from './pricing';
import { runningActualCompute } from './status-progress';

/** Dynamo REMOVE fragment for EC2 launch fields after a terminal worker outcome. */
export const REMOVE_EPHEMERAL_WORKER_ATTRS =
  'instanceId, instanceType, spot, workerStartedAt, launchLockUntil, deadlineAt, maxRuntimeMinutes';

export function snapshotActualComputeFields(
  item: Record<string, unknown>,
  finishedAtMs: number,
): Record<string, number | string> {
  const started = item.workerStartedAt as number | undefined;
  const instanceType = item.instanceType as string | undefined;
  const spot = item.spot as boolean | undefined;
  const out: Record<string, number | string> = { workerFinishedAt: finishedAtMs };
  if (!started || !instanceType) return out;
  const live = runningActualCompute(
    started,
    finishedAtMs,
    instanceType,
    spot,
    finishedAtMs,
    actualComputeCost,
  );
  if (live.seconds != null) out.actualComputeSeconds = live.seconds;
  if (live.usd != null) out.actualComputeUsd = live.usd;
  out.finalInstanceType = instanceType;
  return out;
}

export function persistedActualCompute(
  item: Record<string, unknown>,
): { seconds?: number; usd?: number } | null {
  const seconds = item.actualComputeSeconds as number | undefined;
  const usd = item.actualComputeUsd as number | undefined;
  if (seconds == null || usd == null) return null;
  return { seconds, usd };
}

export function metaTerminalWorkerUpdate(
  item: Record<string, unknown>,
  finishedAtMs: number,
  attrs: { state: string; phase: string; lastError: string; updatedAt?: number },
) {
  const updatedAt = attrs.updatedAt ?? finishedAtMs;
  const snapshot = snapshotActualComputeFields(item, finishedAtMs);
  const names: Record<string, string> = { '#s': 'state' };
  const values: Record<string, unknown> = {
    ':s': attrs.state,
    ':ph': attrs.phase,
    ':e': attrs.lastError,
    ':t': updatedAt,
    ':wf': snapshot.workerFinishedAt,
  };
  const setParts = ['#s = :s', 'phase = :ph', 'lastError = :e', 'updatedAt = :t', 'workerFinishedAt = :wf'];
  if (snapshot.actualComputeSeconds != null) {
    setParts.push('actualComputeSeconds = :acs', 'actualComputeUsd = :acu', 'finalInstanceType = :fit');
    values[':acs'] = snapshot.actualComputeSeconds;
    values[':acu'] = snapshot.actualComputeUsd;
    values[':fit'] = snapshot.finalInstanceType;
  }
  return {
    UpdateExpression: `SET ${setParts.join(', ')} REMOVE ${REMOVE_EPHEMERAL_WORKER_ATTRS}`,
    ExpressionAttributeNames: names,
    ExpressionAttributeValues: values,
  };
}
