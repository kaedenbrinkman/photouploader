import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, PutCommand } from '@aws-sdk/lib-dynamodb';
import { GetParameterCommand, PutParameterCommand, SSMClient } from '@aws-sdk/client-ssm';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { getMethod, getPath, json } from './http';
import { issueSessionToken } from './session';

const ssm = new SSMClient({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const GOOGLE_OAUTH_PARAM = process.env.GOOGLE_OAUTH_PARAM!;

const SCOPES = [
  'openid',
  'email',
  'profile',
  'https://www.googleapis.com/auth/photoslibrary.appendonly',
  'https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata',
].join(' ');

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const path = getPath(event);
  if (getMethod(event) !== 'POST' || !path.endsWith('/auth/google')) {
    return json(404, { error: 'not found' });
  }

  const body = event.body ? JSON.parse(event.body) : {};
  const serverAuthCode = String(body.serverAuthCode ?? '');
  if (!serverAuthCode) {
    return json(400, { error: 'serverAuthCode required' });
  }

  const clientJson = await loadGoogleClient();
  const clientId = clientJson.web?.client_id ?? clientJson.installed?.client_id;
  const clientSecret = clientJson.web?.client_secret ?? clientJson.installed?.client_secret;
  if (!clientId || !clientSecret) {
    return json(500, { error: 'google oauth client misconfigured in SSM' });
  }

  const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code: serverAuthCode,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: '',
      grant_type: 'authorization_code',
    }),
  });
  if (!tokenRes.ok) {
    return json(502, { error: 'google token exchange failed', detail: await tokenRes.text() });
  }

  const tokens = (await tokenRes.json()) as {
    id_token?: string;
    refresh_token?: string;
    access_token?: string;
  };
  const profile = await resolveProfile(tokens, clientId);
  if (!profile?.sub) return json(502, { error: 'could not resolve google user id' });

  if (tokens.refresh_token) {
    const secretPayload = JSON.stringify({
      type: 'authorized_user',
      client_id: clientId,
      client_secret: clientSecret,
      refresh_token: tokens.refresh_token,
    });
    await ssm.send(
      new PutParameterCommand({
        Name: `/photopipeline/users/${profile.sub}/gphotos-token`,
        Type: 'SecureString',
        Value: secretPayload,
        Overwrite: true,
      }),
    );
  }

  await ddb.send(
    new PutCommand({
      TableName: process.env.TABLE_NAME,
      Item: {
        pk: `USER#${profile.sub}`,
        sk: 'PROFILE',
        userId: profile.sub,
        email: profile.email ?? null,
        gphotosLinked: Boolean(tokens.refresh_token),
        scopes: SCOPES,
        updatedAt: Date.now(),
      },
    }),
  );

  const session = await issueSessionToken({ sub: profile.sub, email: profile.email });
  return json(200, {
    sessionToken: session.token,
    expiresIn: session.expiresIn,
    sub: profile.sub,
    email: profile.email,
    gphotosLinked: Boolean(tokens.refresh_token),
  });
};

async function resolveProfile(
  tokens: { id_token?: string; access_token?: string },
  clientId: string,
): Promise<{ sub?: string; email?: string } | undefined> {
  const fromId = parseAndVerifyIdToken(tokens.id_token, clientId);
  if (fromId?.sub) return fromId;
  if (tokens.access_token) {
    return fetchUserInfo(tokens.access_token);
  }
  return undefined;
}

function parseAndVerifyIdToken(
  idToken: string | undefined,
  clientId: string,
): { sub?: string; email?: string } | undefined {
  if (!idToken) return undefined;
  const parts = idToken.split('.');
  if (parts.length < 2) return undefined;
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')) as {
    sub?: string;
    email?: string;
    aud?: string | string[];
  };
  const aud = payload.aud;
  const audOk = aud === clientId || (Array.isArray(aud) && aud.includes(clientId));
  if (!audOk) return undefined;
  return { sub: payload.sub, email: payload.email };
}

async function fetchUserInfo(accessToken: string): Promise<{ sub?: string; email?: string } | undefined> {
  const res = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) return undefined;
  const info = (await res.json()) as { sub?: string; email?: string };
  return { sub: info.sub, email: info.email };
}

async function loadGoogleClient(): Promise<any> {
  const r = await ssm.send(
    new GetParameterCommand({ Name: GOOGLE_OAUTH_PARAM, WithDecryption: true }),
  );
  return JSON.parse(r.Parameter!.Value!);
}
