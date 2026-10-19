import { isVideoFileName } from './media-kind';

const WORKING_SET_GB = 20;
const MIN_ROOT_GB = 30;
const MAX_ROOT_GB = 200;

export interface ManifestSizeEntry {
  size_bytes: number;
  original_name?: string;
}

/** gp3 root volume for ephemeral workers (DeleteOnTermination in RunInstances). */
export function computeRootVolumeGiB(files: ManifestSizeEntry[]): number {
  let largestVideoBytes = 0;
  for (const f of files ?? []) {
    const name = f.original_name ?? '';
    if (!isVideoFileName(name)) continue;
    largestVideoBytes = Math.max(largestVideoBytes, Number(f.size_bytes) || 0);
  }
  const videoGb = (largestVideoBytes * 2) / 1024 ** 3;
  const raw = WORKING_SET_GB + videoGb;
  return Math.min(MAX_ROOT_GB, Math.max(MIN_ROOT_GB, Math.ceil(raw)));
}

export function ebsGp3ShootUsd(
  volumeGiB: number,
  processingMinutes: number,
  gp3UsdPerGbMonth: number,
): number {
  const gb = Math.max(0, volumeGiB);
  const mins = Math.max(0, processingMinutes);
  const hours = mins / 60;
  const monthHours = 730;
  return round((gb * gp3UsdPerGbMonth * hours) / monthHours);
}

function round(n: number) {
  return Math.round(n * 10000) / 10000;
}
