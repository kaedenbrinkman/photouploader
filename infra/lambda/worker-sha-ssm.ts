import { GetParameterCommand, SSMClient } from '@aws-sdk/client-ssm';

const ssm = new SSMClient({});

const DEFAULT_BUNDLE_PARAM = '/photopipeline/worker-bundle-sha256';
const DEFAULT_LIGHTCRAFT_PARAM = '/photopipeline/lightcraft-sha256';

let cache: { bundle: string; lightcraft: string; at: number } | null = null;
const CACHE_MS = 60_000;

function normalizeSha256(v: string): string {
  return v.trim().toLowerCase();
}

export async function loadWorkerSha256FromSsm(): Promise<{
  bundleSha256: string;
  lightcraftSha256: string;
}> {
  const now = Date.now();
  if (cache && now - cache.at < CACHE_MS) {
    return { bundleSha256: cache.bundle, lightcraftSha256: cache.lightcraft };
  }
  const bundleParam = process.env.WORKER_BUNDLE_SHA256_PARAM ?? DEFAULT_BUNDLE_PARAM;
  const lightcraftParam = process.env.LIGHTCRAFT_SHA256_PARAM ?? DEFAULT_LIGHTCRAFT_PARAM;

  const [bundleRes, lightcraftRes] = await Promise.all([
    ssm.send(new GetParameterCommand({ Name: bundleParam })),
    ssm.send(new GetParameterCommand({ Name: lightcraftParam })),
  ]);
  const bundleSha256 = normalizeSha256(bundleRes.Parameter?.Value ?? '');
  const lightcraftSha256 = normalizeSha256(lightcraftRes.Parameter?.Value ?? '');
  if (!bundleSha256 || bundleSha256.length !== 64) {
    throw new Error(`invalid SSM value at ${bundleParam}`);
  }
  if (!lightcraftSha256 || lightcraftSha256.length !== 64) {
    throw new Error(`invalid SSM value at ${lightcraftParam}`);
  }
  cache = { bundle: bundleSha256, lightcraft: lightcraftSha256, at: now };
  return { bundleSha256, lightcraftSha256 };
}
