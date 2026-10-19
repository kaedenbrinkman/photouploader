import {
  invalidPartErrorCode,
  isNoSuchUpload,
  isRetryableCompleteError,
} from '../lambda/presign-complete';

describe('presign-complete errors', () => {
  it('detects invalid part codes', () => {
    expect(invalidPartErrorCode({ name: 'InvalidPart' })).toBe('InvalidPart');
    expect(invalidPartErrorCode({ Code: 'InvalidPartOrder' })).toBe('InvalidPartOrder');
  });

  it('detects NoSuchUpload', () => {
    expect(isNoSuchUpload({ Code: 'NoSuchUpload' })).toBe(true);
  });

  it('marks throttling as retryable', () => {
    expect(isRetryableCompleteError({ name: 'SlowDown' })).toBe(true);
    expect(isRetryableCompleteError({ $metadata: { httpStatusCode: 503 } })).toBe(true);
    expect(isRetryableCompleteError({ name: 'InvalidPart' })).toBe(false);
  });
});
