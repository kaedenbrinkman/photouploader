import {
  findMissingManifestObjects,
  ManifestValidationError,
  rawKeyForFile,
  validateManifest,
} from '../lambda/manifest-validate';

describe('validateManifest', () => {
  const base = {
    shoot_id: 's1',
    user_id: 'u1',
    files: [{ sha256: 'a'.repeat(64), size_bytes: 100, original_name: 'x.CR3' }],
  };

  it('accepts valid manifest', () => {
    expect(() => validateManifest(base, 's1', 'u1')).not.toThrow();
  });

  it('rejects shoot/user mismatch', () => {
    expect(() => validateManifest(base, 's2', 'u1')).toThrow(ManifestValidationError);
    expect(() => validateManifest(base, 's1', 'u2')).toThrow(ManifestValidationError);
  });

  it('rejects duplicate sha', () => {
    const dup = {
      ...base,
      files: [
        { sha256: 'b'.repeat(64), size_bytes: 1, original_name: 'a.CR3' },
        { sha256: 'b'.repeat(64), size_bytes: 2, original_name: 'b.CR3' },
      ],
    };
    expect(() => validateManifest(dup, 's1', 'u1')).toThrow(ManifestValidationError);
  });

  it('rejects duplicate s3_key', () => {
    const dupKey = {
      ...base,
      files: [
        {
          sha256: 'a'.repeat(64),
          size_bytes: 100,
          s3_key: 'shoots/s1/raw/custom-a.CR3',
          original_name: 'a.CR3',
        },
        {
          sha256: 'b'.repeat(64),
          size_bytes: 200,
          s3_key: 'shoots/s1/raw/custom-a.CR3',
          original_name: 'b.CR3',
        },
      ],
    };
    expect(() => validateManifest(dupKey, 's1', 'u1')).toThrow(/duplicate s3_key/);
  });
});

describe('rawKeyForFile', () => {
  it('uses manifest s3_key when present', () => {
    const sha = 'c'.repeat(64);
    const custom = `shoots/s1/raw/upload-ref-not-sha${'.CR3'}`;
    const key = rawKeyForFile('s1', {
      sha256: sha,
      size_bytes: 1,
      s3_key: custom,
      original_name: 'IMG.CR3',
    });
    expect(key).toBe(custom);
    expect(key).not.toContain(sha);
  });

  it('falls back to sha256 path when s3_key absent', () => {
    const sha = 'd'.repeat(64);
    expect(rawKeyForFile('s1', { sha256: sha, size_bytes: 1, original_name: 'x.JPG' })).toBe(
      `shoots/s1/raw/${sha}.JPG`,
    );
  });
});

describe('findMissingManifestObjects', () => {
  it('verifies custom s3_key location (finalize would succeed)', () => {
    const sha = 'e'.repeat(64);
    const customKey = 'shoots/s1/raw/metadata-upload-ref.CR3';
    const files = [
      {
        sha256: sha,
        size_bytes: 42_000_000,
        s3_key: customKey,
        original_name: 'DSC_0001.CR3',
      },
    ];
    const sizes: Record<string, number> = { [customKey]: 42_000_000 };
    const missing = findMissingManifestObjects('s1', files, (key) => sizes[key] ?? null);
    expect(missing).toHaveLength(0);
    const missingIfWrongShaPath = findMissingManifestObjects(
      's1',
      [{ ...files[0], s3_key: undefined }],
      (key) => sizes[key] ?? null,
    );
    expect(missingIfWrongShaPath).toHaveLength(1);
  });
});
