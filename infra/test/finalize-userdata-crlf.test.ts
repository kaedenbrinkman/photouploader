import { buildUserData } from '../lambda/user-data-build';

const baseParams = {
  shootId: 'shoot-test',
  userId: 'user-test',
  bucket: 'bucket-test',
  table: 'table-test',
  tokenParam: '/photopipeline/users/user-test/gphotos-token',
  settingsHash: 'develop.auto-longEdge0-q90',
  albumTitle: 'Album',
  bundleKey: 'releases/photopipeline-worker.tar.gz',
  bundleSha256: 'a'.repeat(64),
  lightcraftKey: 'releases/lightcraft-cli',
  lightcraftSha256: 'b'.repeat(64),
  maxRuntimeMinutes: 120,
};

describe('finalize EC2 user-data line endings', () => {
  it('buildUserData contains no carriage returns (CRLF-safe)', () => {
    const bootstrapWithCrLf = 'set -euo pipefail\r\nlog() { echo test; }\r\n';
    const userData = buildUserData(baseParams, bootstrapWithCrLf);
    expect(userData).not.toMatch(/\r/);
    expect(userData).toContain('#!/bin/bash\n');
    expect(userData).toContain('set -euo pipefail\n');
  });
});
