import { sha256FromHead } from '../lambda/worker-artifacts';

describe('worker-artifacts', () => {
  it('reads ChecksumSHA256 as hex', () => {
    const hex = 'b061a429a89d5cafaf7a504906bbfbf36a5f7ae28f7ce21a8854cdc608fee738';
    const b64 = Buffer.from(hex, 'hex').toString('base64');
    expect(sha256FromHead({ ChecksumSHA256: b64 })).toBe(hex);
  });

  it('reads metadata sha256', () => {
    expect(sha256FromHead({ Metadata: { sha256: 'AbCd' } })).toBe('abcd');
  });
});
