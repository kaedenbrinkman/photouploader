import {
  contentTypeForOriginalName,
  isVideoFileName,
  rawExtensionForName,
  splitManifestFiles,
} from '../lambda/media-kind';

describe('media-kind', () => {
  it('detects video extensions', () => {
    expect(isVideoFileName('clip.MP4')).toBe(true);
    expect(isVideoFileName('clip.MOV')).toBe(true);
    expect(isVideoFileName('IMG_0001.CR3')).toBe(false);
  });

  it('maps raw keys for video', () => {
    expect(rawExtensionForName('a.MP4')).toBe('.MP4');
    expect(contentTypeForOriginalName('a.MOV')).toBe('video/quicktime');
  });

  it('splits manifest files and sums video bytes', () => {
    const split = splitManifestFiles([
      { original_name: 'a.CR3', size_bytes: 100 },
      { original_name: 'b.MP4', size_bytes: 1_000_000_000 },
    ]);
    expect(split.photoCount).toBe(1);
    expect(split.videoCount).toBe(1);
    expect(split.videoBytes).toBe(1_000_000_000);
  });
});
