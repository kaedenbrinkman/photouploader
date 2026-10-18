import { ec2ErrorCode, isRetryableEc2LaunchError } from '../lambda/ec2-launch';

describe('ec2-launch', () => {
  it('reads SDK error name', () => {
    expect(ec2ErrorCode({ name: 'InsufficientInstanceCapacity', message: 'x' })).toBe(
      'InsufficientInstanceCapacity',
    );
    expect(isRetryableEc2LaunchError({ name: 'PendingVerification' })).toBe(true);
    expect(isRetryableEc2LaunchError({ name: 'UnauthorizedOperation' })).toBe(false);
  });
});
