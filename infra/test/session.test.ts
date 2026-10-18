import { SignJWT } from 'jose';
import { issueSessionToken, verifySessionJwt } from '../lambda/session';

describe('session JWT', () => {
  beforeEach(() => {
    process.env.SESSION_JWT_SECRET = 'test-secret-at-least-32-bytes-long!!';
  });

  it('issues and verifies a session token', async () => {
    const { token } = await issueSessionToken({ sub: 'google-sub-123', email: 'me@example.com' });
    const claims = await verifySessionJwt(token);
    expect(claims.sub).toBe('google-sub-123');
    expect(claims.email).toBe('me@example.com');
  });

  it('rejects tampered tokens', async () => {
    const bad = await new SignJWT({ email: 'x@y.com' })
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject('other')
      .setIssuedAt()
      .setExpirationTime('1h')
      .setIssuer('photopipeline')
      .sign(new TextEncoder().encode('wrong-secret-wrong-secret-wrong!!'));
    await expect(verifySessionJwt(bad)).rejects.toThrow();
  });
});
