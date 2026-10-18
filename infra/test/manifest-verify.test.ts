interface ManifestFile {
  sha256: string;
  size_bytes: number;
  original_name?: string;
}

export function missingManifestKeys(
  shootId: string,
  files: ManifestFile[],
  head: (key: string) => number | null,
): string[] {
  const missing: string[] = [];
  for (const f of files) {
    const ext = (f.original_name ?? '').toUpperCase().endsWith('.JPG') ? '.JPG' : '.CR3';
    const key = `shoots/${shootId}/raw/${f.sha256.toLowerCase()}${ext}`;
    const size = head(key);
    if (size === null || size !== f.size_bytes) missing.push(key);
  }
  return missing;
}

describe('missingManifestKeys', () => {
  it('flags missing and size mismatch', () => {
    const files = [{ sha256: 'a'.repeat(64), size_bytes: 100, original_name: 'x.CR3' }];
    expect(missingManifestKeys('s1', files, () => null)).toHaveLength(1);
    expect(missingManifestKeys('s1', files, () => 99)).toHaveLength(1);
    expect(missingManifestKeys('s1', files, () => 100)).toHaveLength(0);
  });
});
