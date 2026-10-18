/** EC2 RunInstances error classification for finalize retry behavior. */

export const RETRYABLE_EC2_LAUNCH_CODES = new Set([
  'PendingVerification',
  'ServiceLinkedRoleCreationNotPermitted',
  'InsufficientInstanceCapacity',
]);

export function ec2ErrorCode(err: unknown): string | undefined {
  if (!err || typeof err !== 'object') return undefined;
  const o = err as Record<string, unknown>;
  if (typeof o.name === 'string' && o.name.length) return o.name;
  if (typeof o.Code === 'string' && o.Code.length) return o.Code;
  const nested = o.Error as Record<string, unknown> | undefined;
  if (nested && typeof nested.Code === 'string') return nested.Code;
  return undefined;
}

export function ec2ErrorMessage(err: unknown): string {
  if (!err) return 'unknown error';
  if (typeof err === 'string') return err;
  if (err instanceof Error) return err.message;
  if (typeof err === 'object') {
    const o = err as Record<string, unknown>;
    if (typeof o.message === 'string') return o.message;
  }
  return String(err);
}

export function isRetryableEc2LaunchError(err: unknown): boolean {
  const code = ec2ErrorCode(err);
  return code != null && RETRYABLE_EC2_LAUNCH_CODES.has(code);
}
