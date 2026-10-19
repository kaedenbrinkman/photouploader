import { HeadObjectCommand, S3Client } from '@aws-sdk/client-s3';
import type { ManifestFile } from './manifest-validate';
import { isVideoManifestFile } from './media-kind';

export type WorkerLaunchProfile = 'render' | 'publish_only';

const HEAD_CONCURRENCY = 24;

async function photoJpegExists(
  s3: S3Client,
  bucket: string,
  shootId: string,
  settingsHash: string,
  contentSha: string,
): Promise<boolean> {
  const key = `shoots/${shootId}/jpeg/${settingsHash}/${contentSha.toLowerCase()}.jpg`;
  try {
    await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    return true;
  } catch {
    return false;
  }
}

/**
 * When every manifest photo already has a rendered JPEG in S3, worker only publishes (+ videos).
 * Any HeadObject failure → fall back to full render instance.
 */
export async function detectWorkerLaunchProfile(
  s3: S3Client,
  bucket: string,
  shootId: string,
  settingsHash: string,
  files: ManifestFile[],
): Promise<WorkerLaunchProfile> {
  const photos = files.filter((f) => !isVideoManifestFile(f));
  if (photos.length === 0) return 'publish_only';

  let next = 0;
  let missing = false;
  const workers = Array.from({ length: Math.min(HEAD_CONCURRENCY, photos.length) }, async () => {
    while (!missing) {
      const i = next++;
      if (i >= photos.length) break;
      const sha = String(photos[i]!.sha256).toLowerCase();
      const ok = await photoJpegExists(s3, bucket, shootId, settingsHash, sha);
      if (!ok) missing = true;
    }
  });
  await Promise.all(workers);
  return missing ? 'render' : 'publish_only';
}
