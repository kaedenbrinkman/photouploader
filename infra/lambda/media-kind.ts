/** Photo vs video classification for manifest keys and worker routing. */

const VIDEO_EXT = new Set(['.MP4', '.MOV', '.M4V', '.AVI', '.MKV', '.3GP']);

export function isVideoFileName(originalName: string | undefined | null): boolean {
  const u = String(originalName ?? '').toUpperCase();
  for (const ext of VIDEO_EXT) {
    if (u.endsWith(ext)) return true;
  }
  return false;
}

export function isVideoContentType(contentType: string | undefined | null): boolean {
  return String(contentType ?? '').toLowerCase().startsWith('video/');
}

export function isVideoManifestFile(f: {
  original_name?: string;
  content_type?: string;
}): boolean {
  return isVideoContentType(f.content_type) || isVideoFileName(f.original_name);
}

export function rawExtensionForName(originalName: string | undefined | null): string {
  const u = String(originalName ?? '').toUpperCase();
  if (u.endsWith('.JPG') || u.endsWith('.JPEG')) return '.JPG';
  if (u.endsWith('.CR2')) return '.CR2';
  if (u.endsWith('.MP4')) return '.MP4';
  if (u.endsWith('.MOV')) return '.MOV';
  if (u.endsWith('.M4V')) return '.M4V';
  if (u.endsWith('.HEIC')) return '.HEIC';
  return '.CR3';
}

export function contentTypeForOriginalName(originalName: string | undefined | null): string {
  const u = String(originalName ?? '').toUpperCase();
  if (u.endsWith('.MP4') || u.endsWith('.M4V')) return 'video/mp4';
  if (u.endsWith('.MOV')) return 'video/quicktime';
  if (u.endsWith('.JPG') || u.endsWith('.JPEG')) return 'image/jpeg';
  if (u.endsWith('.HEIC')) return 'image/heic';
  return 'image/x-canon-cr3';
}

export function splitManifestFiles<T extends { original_name?: string; content_type?: string; size_bytes?: number }>(
  files: T[],
): { photos: T[]; videos: T[]; photoCount: number; videoCount: number; videoBytes: number } {
  const photos: T[] = [];
  const videos: T[] = [];
  let videoBytes = 0;
  for (const f of files) {
    if (isVideoManifestFile(f)) {
      videos.push(f);
      videoBytes += Number(f.size_bytes ?? 0);
    } else {
      photos.push(f);
    }
  }
  return { photos, videos, photoCount: photos.length, videoCount: videos.length, videoBytes };
}
