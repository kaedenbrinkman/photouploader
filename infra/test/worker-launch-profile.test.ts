import { detectWorkerLaunchProfile } from '../lambda/worker-launch-profile';

function mockS3(existingJpegShas: Set<string>) {
  return {
    send: jest.fn(async (cmd: { input?: { Key?: string } }) => {
      const key = cmd.input?.Key ?? '';
      const m = key.match(/\/jpeg\/[^/]+\/([a-f0-9]{64})\.jpg$/);
      if (m && existingJpegShas.has(m[1]!)) return {};
      throw new Error('NotFound');
    }),
  };
}

describe('detectWorkerLaunchProfile', () => {
  const shootId = 'shoot1';
  const settingsHash = 'develop.auto-longEdge0-q90';

  it('returns publish_only when every photo JPEG exists in S3', async () => {
    const sha = 'a'.repeat(64);
    const s3 = mockS3(new Set([sha]));
    const profile = await detectWorkerLaunchProfile(
      s3 as any,
      'bucket',
      shootId,
      settingsHash,
      [{ sha256: sha, size_bytes: 1, original_name: 'x.CR3' }],
    );
    expect(profile).toBe('publish_only');
  });

  it('returns render when any photo JPEG is missing', async () => {
    const sha = 'b'.repeat(64);
    const s3 = mockS3(new Set());
    const profile = await detectWorkerLaunchProfile(
      s3 as any,
      'bucket',
      shootId,
      settingsHash,
      [{ sha256: sha, size_bytes: 1, original_name: 'x.CR3' }],
    );
    expect(profile).toBe('render');
  });

  it('returns publish_only for video-only manifest', async () => {
    const s3 = mockS3(new Set());
    const profile = await detectWorkerLaunchProfile(
      s3 as any,
      'bucket',
      shootId,
      settingsHash,
      [{ sha256: 'c'.repeat(64), size_bytes: 1, original_name: 'x.MP4' }],
    );
    expect(profile).toBe('publish_only');
  });
});
