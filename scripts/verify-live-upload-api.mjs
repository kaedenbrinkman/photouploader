#!/usr/bin/env node
/**
 * Optional live check against deployed API.
 * Usage: PHOTOPIPELINE_SESSION_TOKEN=eyJ... node scripts/verify-live-upload-api.mjs
 */
const base = process.env.PHOTOPIPELINE_API_BASE ?? 'https://3jamg4s1j9.execute-api.us-west-2.amazonaws.com';
const token = process.env.PHOTOPIPELINE_SESSION_TOKEN;
if (!token) {
  console.error('Set PHOTOPIPELINE_SESSION_TOKEN (session JWT from POST /auth/google).');
  process.exit(1);
}

const shootId = `verify-${Date.now()}`;
const sha = 'b'.repeat(64);

async function req(method, path, body) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} HTTP ${res.status}: ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : {};
}

(async () => {
  await req('POST', '/shoots', { action: 'create_shoot', shootId, albumTitle: 'verify' });
  const mp = await req('POST', `/shoots/${shootId}/presign`, {
    action: 'create_multipart',
    sha256: sha,
    originalName: 'TEST.CR3',
  });
  const part = await req('POST', `/shoots/${shootId}/presign`, {
    action: 'presign_part',
    sha256: sha,
    partNumber: 1,
    uploadId: mp.uploadId,
    originalName: 'TEST.CR3',
    sizeBytes: 4,
  });
  const put = await fetch(part.url, { method: 'PUT', body: Buffer.from('test') });
  if (!put.ok) throw new Error(`S3 PUT HTTP ${put.status}`);
  await req('POST', `/shoots/${shootId}/presign`, {
    action: 'complete_multipart',
    sha256: sha,
    key: mp.key,
    uploadId: mp.uploadId,
    parts: [{ PartNumber: 1, ETag: put.headers.get('etag')?.replaceAll('"', '') ?? 'x' }],
  });
  await req('DELETE', `/shoots/${shootId}`);
  console.log('OK: create_shoot → multipart → presign → PUT → complete → delete');
})().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
