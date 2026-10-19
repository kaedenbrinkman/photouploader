import { GetParameterCommand, SSMClient } from '@aws-sdk/client-ssm';
import { SignJWT, jwtVerify } from 'jose';

const ssm = new SSMClient({});

const SESSION_PARAM = process.env.SESSION_JWT_PARAM ?? '/photopipeline/session-jwt-secret';
const SESSION_TTL_SECONDS = Number(process.env.SESSION_TTL_SECONDS ?? 60 * 60 * 24 * 7);

let cachedSecret: Uint8Array | undefined;

export interface SessionClaims {
  sub: string;
  email?: string;
}

export async function issueSessionToken(claims: SessionClaims): Promise<{ token: string; expiresIn: number }> {
  const secret = await loadSessionSecret();
  const expiresIn = SESSION_TTL_SECONDS;
  const token = await new SignJWT({ email: claims.email })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(claims.sub)
    .setIssuedAt()
    .setExpirationTime(`${expiresIn}s`)
    .setIssuer('photopipeline')
    .sign(secret);
  return { token, expiresIn };
}

export async function verifySessionJwt(token: string): Promise<SessionClaims> {
  const secret = await loadSessionSecret();
  const { payload } = await jwtVerify(token, secret, { issuer: 'photopipeline' });
  const sub = payload.sub;
  if (!sub) throw new Error('missing sub');
  return { sub: String(sub), email: payload.email ? String(payload.email) : undefined };
}

async function loadSessionSecret(): Promise<Uint8Array> {
  if (cachedSecret) return cachedSecret;
  const inline = process.env.SESSION_JWT_SECRET;
  if (inline) {
    cachedSecret = new TextEncoder().encode(inline);
    return cachedSecret;
  }
  const r = await ssm.send(
    new GetParameterCommand({ Name: SESSION_PARAM, WithDecryption: true }),
  );
  const value = r.Parameter?.Value;
  if (!value) throw new Error('session jwt secret missing');
  cachedSecret = new TextEncoder().encode(value);
  return cachedSecret;
}
