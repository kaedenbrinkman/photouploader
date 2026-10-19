import { findMissingManifestObjects, rawKeyForFile } from '../lambda/manifest-validate';

describe('missingManifestKeys', () => {
  it('flags missing and size mismatch using sha fallback', () => {
    const files = [{ sha256: 'a'.repeat(64), size_bytes: 100, original_name: 'x.CR3' }];
    expect(findMissingManifestObjects('s1', files, () => null)).toHaveLength(1);
    expect(findMissingManifestObjects('s1', files, () => 99)).toHaveLength(1);
    expect(findMissingManifestObjects('s1', files, () => 100)).toHaveLength(0);
  });

  it('uses s3_key from manifest when checking S3', () => {
    const custom = 'shoots/s1/raw/my-upload-key.CR3';
    const files = [
      {
        sha256: 'b'.repeat(64),
        size_bytes: 500,
        s3_key: custom,
        original_name: 'x.CR3',
      },
    ];
    expect(findMissingManifestObjects('s1', files, (k) => (k === custom ? 500 : null))).toHaveLength(0);
    expect(rawKeyForFile('s1', files[0])).toBe(custom);
  });
});
