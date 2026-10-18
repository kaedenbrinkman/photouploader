import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';

export function userIdFromEvent(event: APIGatewayProxyEventV2): string | undefined {
  const jwt = event.requestContext.authorizer?.jwt;
  if (jwt?.claims?.sub) return String(jwt.claims.sub);
  return undefined;
}

export function json(status: number, body: unknown): APIGatewayProxyResultV2 {
  return {
    statusCode: status,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

export function getPath(event: APIGatewayProxyEventV2): string {
  return event.rawPath ?? event.requestContext.http.path;
}

export function getMethod(event: APIGatewayProxyEventV2): string {
  return event.requestContext.http.method;
}
