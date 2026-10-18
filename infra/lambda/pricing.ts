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
  imageCount: number,
  totalBytesRaw: number,
  opts?: { transferAcceleration?: boolean },
): CostEstimate {
  if (imageCount <= 0) throw new Error('imageCount must be positive');
  const p = pricingTable as any;
  const ec2 = p.ec2;
  let instanceType = ec2.instanceTypeOrder[0];
  let spot = Boolean(ec2.preferSpot);
  if (imageCount < 50) instanceType = ec2.instanceTypeOrder[1] ?? instanceType;
  const par = ec2.parallelism[instanceType];
  const processingSeconds = ec2.bootOverheadMinutes * 60 + (imageCount * ec2.secondsPerImageAtReferenceParallelism) / par;
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
  const rateKey = spot ? 'spotUsdPerHour' : 'onDemandUsdPerHour';
  const hourly = p.ec2[rateKey][instanceType] ?? p.ec2.onDemandUsdPerHour['c7i.2xlarge'];
  return round(hourly * (computeSeconds / 3600));
}

function round(n: number) {
  return Math.round(n * 10000) / 10000;
}
