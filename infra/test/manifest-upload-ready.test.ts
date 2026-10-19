import {
  manifestKeysMissingFromDynamo,
  pickSpotCheckIndices,
  SPOT_CHECK_MAX_FILES,
} from '../lambda/manifest-upload-ready';
import type { ManifestFile } from '../lambda/manifest-validate';

function manifestFile(i: number, sizeBytes = 4_000_000): ManifestFile {
  const hex = i.toString(16).padStart(64, '0');
  return { sha256: hex, size_bytes: sizeBytes, original_name: `IMG_${String(i).padStart(4, '0')}.CR3` };
}

describe('manifest-upload-ready', () => {
  it('pickSpotCheckIndices covers 374 files with at most 20 samples', () => {
    const indices = pickSpotCheckIndices(374, SPOT_CHECK_MAX_FILES);
    expect(indices).toHaveLength(SPOT_CHECK_MAX_FILES);
    expect(indices[0]).toBe(0);
    expect(indices[indices.length - 1]).toBeLessThan(374);
    expect(new Set(indices).size).toBe(indices.length);
  });

  it('374-file manifest passes when all Dynamo rows are UPLOADED with matching sizes', () => {
    const files = Array.from({ length: 374 }, (_, i) => manifestFile(i));
    const rows = files.map((f) => ({
      sha256: f.sha256,
      state: 'UPLOADED',
      sizeBytes: f.size_bytes,
    }));
    const missing = manifestKeysMissingFromDynamo('shoot-374', files, rows);
    expect(missing).toHaveLength(0);
  });

  it('flags manifest entries missing UPLOADED in Dynamo', () => {
    const files = [manifestFile(1), manifestFile(2)];
    const rows = [{ sha256: files[0]!.sha256, state: 'UPLOADED', sizeBytes: files[0]!.size_bytes }];
    const missing = manifestKeysMissingFromDynamo('s1', files, rows);
    expect(missing.length).toBeGreaterThan(0);
  });
});
