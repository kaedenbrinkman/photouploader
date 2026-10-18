import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { GetParameterCommand, PutParameterCommand, SSMClient } from '@aws-sdk/client-ssm';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { randomBytes } from 'crypto';
import { getPath, json } from './http';

const ssm = new SSMClient({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const PHOTOS_SCOPES = [
  'https://www.googleapis.com/auth/photoslibrary.appendonly',
  'https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata',
].join(' ');

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const path = getPath(event);
  const qs = event.queryStringParameters ?? {};
  const clientJson = await loadGoogleClient();

  if (path.endsWith('/start')) {
    const userId = qs.userId;
    if (!userId) return redirect('/error?reason=missing_user');
    const state = `${userId}:${randomBytes(16).toString('hex')}`;
    const redirectUri = `${apiBase(event)}/oauth/photos/callback`;
    const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
    url.searchParams.set('client_id', clientJson.web?.client_id ?? clientJson.installed.client_id);
    url.searchParams.set('redirect_uri', redirectUri);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('scope', PHOTOS_SCOPES);
    url.searchParams.set('access_type', 'offline');
    url.searchParams.set('prompt', 'consent');
    url.searchParams.set('state', state);
    return redirect(url.toString());
  }

  if (path.endsWith('/callback')) {
    const code = qs.code;
    const state = qs.state ?? '';
    const userId = state.split(':')[0];
    if (!code || !userId) return text(400, 'invalid callback');
    const redirectUri = `${apiBase(event)}/oauth/photos/callback`;
    const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: clientJson.web?.client_id ?? clientJson.installed.client_id,
        client_secret: clientJson.web?.client_secret ?? clientJson.installed.client_secret,
        redirect_uri: redirectUri,
        grant_type: 'authorization_code',
      }),
    });
    if (!tokenRes.ok) return text(502, await tokenRes.text());
    const tokens = await tokenRes.json();
    const secretPayload = JSON.stringify({
      type: 'authorized_user',
      client_id: clientJson.web?.client_id ?? clientJson.installed.client_id,
      client_secret: clientJson.web?.client_secret ?? clientJson.installed.client_secret,
      refresh_token: tokens.refresh_token,
    });
    const paramName = `/photopipeline/users/${userId}/gphotos-token`;
    await ssm.send(
      new PutParameterCommand({
        Name: paramName,
        Type: 'SecureString',
        Value: secretPayload,
        Overwrite: true,
      }),
    );
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `USER#${userId}`, sk: 'PROFILE' },
        UpdateExpression: 'SET gphotosLinked = :t, updatedAt = :u',
        ExpressionAttributeValues: { ':t': true, ':u': Date.now() },
      }),
    );
    return redirect('photopipeline://oauth/photos/success');
  }

  return text(404, 'not found');
};

async function loadGoogleClient(): Promise<any> {
  const r = await ssm.send(
    new GetParameterCommand({ Name: process.env.GOOGLE_OAUTH_PARAM!, WithDecryption: true }),
  );
  return JSON.parse(r.Parameter!.Value!);
}

function apiBase(event: { requestContext: { domainName: string; stage?: string } }) {
  const stage = event.requestContext.stage;
  return stage && stage !== '$default' ? `https://${event.requestContext.domainName}/${stage}` : `https://${event.requestContext.domainName}`;
}

function redirect(location: string) {
  return { statusCode: 302, headers: { Location: location } };
}

function text(status: number, body: string) {
  return { statusCode: status, headers: { 'Content-Type': 'text/plain' }, body };
}
