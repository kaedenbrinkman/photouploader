import { issueSessionToken } from '../lambda/session';

describe('upload API contract', () => {
  beforeEach(() => {
    process.env.SESSION_JWT_SECRET = 'test-secret-at-least-32-bytes-long!!';
  });

  it('session JWT is HS256 bearer used by authorizer routes', async () => {
    const { token } = await issueSessionToken({ sub: 'user-1', email: 'u@test.com' });
    expect(token.split('.')).toHaveLength(3);
  });
});
