import pricingTable from '../../config/pricing.json';

export interface CostEstimate {
  imageCount: number;
  totalBytesRaw: number;
  instanceType: string;
  spot: boolean;
  processingMinutes: number;
  computeUsd: number;
  s3StandardMonthUsd: number;
  s3DeepArchiveMonthUsd: number;
  requestsUsd: number;
  transferAccelerationUsd: number;
  totalShootUsd: number;
}

export function getPricingTable() {
  return pricingTable;
}

export function estimateShoot(
  photoCount: number,
  totalBytesRaw: number,
  opts?: { videoCount?: number; transferAcceleration?: boolean },
): CostEstimate {
  const videos = Math.max(0, Math.floor(Number(opts?.videoCount ?? 0)));
  const photos = Math.max(0, Math.floor(Number(photoCount) || 0));
  if (photos <= 0 && videos <= 0) throw new Error('photoCount or videoCount must be positive');
  const imageCount = photos + videos;
  const p = pricingTable as any;
  const ec2 = p.ec2;
  let instanceType = ec2.instanceTypeOrder[0];
  let spot = Boolean(ec2.preferSpot);
  if (imageCount < 50) instanceType = ec2.instanceTypeOrder[1] ?? instanceType;
  const par = ec2.parallelism[instanceType];
  const processingSeconds =
    photos > 0
      ? ec2.bootOverheadMinutes * 60 + (photos * ec2.secondsPerImageAtReferenceParallelism) / par
      : ec2.bootOverheadMinutes * 60 * 0.25;
  const rateKey = spot ? 'spotUsdPerHour' : 'onDemandUsdPerHour';
  const hourly = ec2[rateKey][instanceType];
  const computeUsd = hourly * (processingSeconds / 3600);
  const gb = totalBytesRaw / 1024 ** 3;
  const s3StandardMonthUsd = gb * p.s3.standardUsdPerGbMonth;
  const s3DeepArchiveMonthUsd = gb * p.s3.deepArchiveUsdPerGbMonth;
  const puts = imageCount * 3;
  const requestsUsd = (puts / 1000) * p.s3.putPer1000 + p.lambdaAndApi.estimatePerShootUsd;
  const accelRate = p.s3.transferAccelerationUsdPerGb ?? 0.04;
  const transferAccelerationUsd = opts?.transferAcceleration ? round(gb * accelRate) : 0;
  return {
    imageCount,
    totalBytesRaw,
    instanceType,
    spot,
    processingMinutes: Math.round((processingSeconds / 60) * 10) / 10,
    computeUsd: round(computeUsd),
    s3StandardMonthUsd: round(s3StandardMonthUsd),
    s3DeepArchiveMonthUsd: round(s3DeepArchiveMonthUsd),
    requestsUsd: round(requestsUsd),
    transferAccelerationUsd,
    totalShootUsd: round(computeUsd + s3StandardMonthUsd + requestsUsd + transferAccelerationUsd),
  };
}

export function actualComputeCost(computeSeconds: number, instanceType: string, spot: boolean): number {
  const p = pricingTable as any;
  const seconds = Number.isFinite(computeSeconds) && computeSeconds > 0 ? computeSeconds : 0;
  const rateKey = spot ? 'spotUsdPerHour' : 'onDemandUsdPerHour';
  const fallback = p.ec2?.onDemandUsdPerHour?.['c7i.2xlarge'] ?? 0.34;
  const hourlyRaw = p.ec2?.[rateKey]?.[instanceType] ?? fallback;
  const hourly = Number.isFinite(hourlyRaw) && hourlyRaw > 0 ? hourlyRaw : fallback;
  return round(hourly * (seconds / 3600));
}

function round(n: number) {
  return Math.round(n * 10000) / 10000;
}

const MAX_RUNTIME_MIN_FLOOR = 15;
const MAX_RUNTIME_MIN_CEILING = 120;
const MAX_RUNTIME_BASE_MINUTES = 10;
const MAX_RUNTIME_SAFETY_FACTOR = 2;

/** Per-shoot EC2 wall clock: photo render estimate + video upload bytes (2× margin, clamped 15–120). */
export function computeMaxRuntimeMinutes(photoCount: number, videoBytes = 0): number {
  const photos = Math.max(0, Math.floor(Number(photoCount) || 0));
  const bytes = Math.max(0, Math.floor(Number(videoBytes) || 0));
  const p = pricingTable as any;
  const ec2 = p.ec2;
  const totalFiles = Math.max(1, photos + (bytes > 0 ? 1 : 0));
  const instanceType =
    totalFiles < 50 ? (ec2.instanceTypeOrder[1] ?? ec2.instanceTypeOrder[0]) : ec2.instanceTypeOrder[0];
  const par = ec2.parallelism[instanceType] ?? 4;
  const secondsPerFile = ec2.secondsPerImageAtReferenceParallelism / par;
  const photoMinutes =
    photos > 0 ? Math.ceil(photos * (secondsPerFile / 60) * MAX_RUNTIME_SAFETY_FACTOR) : 0;
  const secondsPerGb = Number(ec2.videoUploadSecondsPerGb ?? 90);
  const videoMinutes =
    bytes > 0
      ? Math.ceil((bytes / 1024 ** 3) * (secondsPerGb / 60) * MAX_RUNTIME_SAFETY_FACTOR)
      : 0;
  const estimate = MAX_RUNTIME_BASE_MINUTES + photoMinutes + videoMinutes;
  return Math.min(MAX_RUNTIME_MIN_CEILING, Math.max(MAX_RUNTIME_MIN_FLOOR, estimate));
}

export function computeDeadlineAt(nowMs: number, maxRuntimeMinutes: number): number {
  const mins = Math.max(1, Math.floor(maxRuntimeMinutes));
  return nowMs + mins * 60_000;
}
