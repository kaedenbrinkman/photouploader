import type { APIGatewayRequestAuthorizerHandlerV2 } from 'aws-lambda';
import { verifySessionJwt } from './session';

export const handler: APIGatewayRequestAuthorizerHandlerV2 = async (event) => {
  try {
    const auth = event.headers?.authorization ?? event.headers?.Authorization ?? '';
    const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
    if (!token) return { isAuthorized: false };
    const claims = await verifySessionJwt(token);
    return {
      isAuthorized: true,
      context: {
        sub: claims.sub,
        email: claims.email ?? '',
      },
    };
  } catch {
    return { isAuthorized: false };
  }
};
