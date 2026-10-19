import { HeadObjectCommand, S3Client } from '@aws-sdk/client-s3';

const s3 = new S3Client({});

function normalizeSha256(v: string): string {
  return v.trim().toLowerCase();
}

/** Compare S3 object checksum/metadata to expected hex SHA256. */
export function sha256FromHead(head: {
  ChecksumSHA256?: string;
  Metadata?: Record<string, string>;
}): string | undefined {
  if (head.ChecksumSHA256) {
    return Buffer.from(head.ChecksumSHA256, 'base64').toString('hex').toLowerCase();
  }
  const meta = head.Metadata?.sha256 ?? head.Metadata?.SHA256;
  if (meta) return normalizeSha256(meta);
  return undefined;
}

export async function verifyWorkerArtifact(
  bucket: string,
  key: string,
  expectedSha256: string,
): Promise<{ ok: true } | { ok: false; reason: string }> {
  let head;
  try {
    head = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
  } catch {
    return { ok: false, reason: `s3://${bucket}/${key} not found` };
  }
  if (!head.ContentLength || head.ContentLength <= 0) {
    return { ok: false, reason: `${key} is empty` };
  }
  const expected = normalizeSha256(expectedSha256);
  const actual = sha256FromHead(head);
  if (!actual) {
    return { ok: false, reason: `${key} missing ChecksumSHA256 or metadata sha256` };
  }
  if (actual !== expected) {
    return { ok: false, reason: `${key} sha256 mismatch (expected ${expected}, got ${actual})` };
  }
  return { ok: true };
}
