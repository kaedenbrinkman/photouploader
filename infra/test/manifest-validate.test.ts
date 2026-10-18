import { ManifestValidationError, validateManifest } from '../lambda/manifest-validate';

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
});
