import {
  CompleteMultipartUploadCommand,
  HeadObjectCommand,
  ListPartsCommand,
  S3Client,
  type CompletedPart,
} from '@aws-sdk/client-s3';

export type CompleteMultipartResult =
  | { ok: true; partCount: number; idempotent?: boolean }
  | { ok: false; status: number; body: Record<string, unknown> };

export function invalidPartErrorCode(err: unknown): string | null {
  const code = errorCode(err);
  if (code === 'InvalidPart' || code === 'InvalidPartOrder') return code;
  const name = errorName(err);
  if (name === 'InvalidPart' || name === 'InvalidPartOrder') return name;
  return null;
}

export function isNoSuchUpload(err: unknown): boolean {
  const code = errorCode(err);
  return code === 'NoSuchUpload' || errorName(err) === 'NoSuchUpload';
}

export function isRetryableCompleteError(err: unknown): boolean {
  const code = errorCode(err);
  const name = errorName(err);
  const retryableCodes = new Set([
    'SlowDown',
    'ServiceUnavailable',
    'RequestTimeout',
    'InternalError',
    'Throttling',
    'TooManyRequestsException',
    'ProvisionedThroughputExceededException',
  ]);
  if (code && retryableCodes.has(code)) return true;
  if (name && retryableCodes.has(name)) return true;
  const status = httpStatus(err);
  return status === 429 || status === 503 || status === 502 || status === 504;
}

function errorCode(err: unknown): string | undefined {
  if (!err || typeof err !== 'object') return undefined;
  const o = err as { Code?: string; code?: string; name?: string };
  return o.Code ?? o.code ?? o.name;
}

function errorName(err: unknown): string | undefined {
  if (!err || typeof err !== 'object') return undefined;
  return (err as { name?: string }).name;
}

function httpStatus(err: unknown): number | undefined {
  if (!err || typeof err !== 'object') return undefined;
  const o = err as { $metadata?: { httpStatusCode?: number }; statusCode?: number };
  return o.$metadata?.httpStatusCode ?? o.statusCode;
}

export async function listCompletedParts(
  s3: S3Client,
  bucket: string,
  key: string,
  uploadId: string,
): Promise<CompletedPart[]> {
  const parts: CompletedPart[] = [];
  let marker: string | undefined;
  do {
    const listed = await s3.send(
      new ListPartsCommand({
        Bucket: bucket,
        Key: key,
        UploadId: uploadId,
        PartNumberMarker: marker,
      }),
    );
    for (const p of listed.Parts ?? []) {
      if (p.PartNumber == null || !p.ETag) continue;
      parts.push({ PartNumber: p.PartNumber, ETag: p.ETag });
    }
    marker = listed.IsTruncated ? listed.NextPartNumberMarker : undefined;
  } while (marker);
  parts.sort((a, b) => (a.PartNumber ?? 0) - (b.PartNumber ?? 0));
  return parts;
}

export async function headObjectExists(
  s3: S3Client,
  bucket: string,
  key: string,
): Promise<boolean> {
  try {
    await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    return true;
  } catch {
    return false;
  }
}

export async function completeShootMultipart(params: {
  s3: S3Client;
  bucket: string;
  key: string;
  uploadId: string;
  log: (msg: string, extra?: Record<string, unknown>) => void;
}): Promise<CompleteMultipartResult> {
  const { s3, bucket, key, uploadId, log } = params;
  let parts: CompletedPart[];
  try {
    parts = await listCompletedParts(s3, bucket, key, uploadId);
  } catch (err) {
    log('complete_multipart list_parts failed', {
      errorName: errorName(err) ?? 'unknown',
      uploadId,
      partCount: 0,
    });
    if (isRetryableCompleteError(err)) {
      return { ok: false, status: 503, body: { error: 'retryable', retryable: true } };
    }
    throw err;
  }

  if (parts.length === 0) {
    return { ok: false, status: 409, body: { error: 'no_parts', uploadId } };
  }

  try {
    await s3.send(
      new CompleteMultipartUploadCommand({
        Bucket: bucket,
        Key: key,
        UploadId: uploadId,
        MultipartUpload: { Parts: parts },
      }),
    );
    return { ok: true, partCount: parts.length };
  } catch (err) {
    const invalid = invalidPartErrorCode(err);
    log('complete_multipart failed', {
      errorName: errorName(err) ?? invalid ?? 'unknown',
      uploadId,
      partCount: parts.length,
    });
    if (invalid) {
      return { ok: false, status: 409, body: { error: invalid, uploadId, partCount: parts.length } };
    }
    if (isNoSuchUpload(err)) {
      const exists = await headObjectExists(s3, bucket, key);
      if (exists) {
        return { ok: true, partCount: parts.length, idempotent: true };
      }
      return { ok: false, status: 409, body: { error: 'NoSuchUpload', uploadId, partCount: parts.length } };
    }
    if (isRetryableCompleteError(err)) {
      return { ok: false, status: 503, body: { error: 'retryable', retryable: true, uploadId } };
    }
    throw err;
  }
}
