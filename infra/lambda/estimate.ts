import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { estimateShoot, getPricingTable } from './pricing';
import { getMethod, getPath, json } from './http';

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const path = getPath(event);
  if (getMethod(event) === 'GET' && path.endsWith('/pricing')) {
    return json(200, { pricing: getPricingTable() });
  }
  const body = event.body ? JSON.parse(event.body) : {};
  const photoCount = Number(body.photoCount ?? body.imageCount ?? 0);
  const videoCount = Number(body.videoCount ?? 0);
  const totalBytesRaw = Number(body.totalBytesRaw ?? 0);
  if ((!photoCount && !videoCount) || totalBytesRaw <= 0) {
    return json(400, { error: 'photoCount or videoCount, and totalBytesRaw required' });
  }
  const transferAcceleration = Boolean(body.transferAcceleration);
  const estimate = estimateShoot(photoCount, totalBytesRaw, { videoCount, transferAcceleration });
  const notes = [
    'Compute assumes spot c7i with on-demand fallback if spot unavailable.',
    'S3 line is first-month Standard storage before Deep Archive transition.',
    'Google Photos storage is billed by Google, not included here.',
  ];
  if (transferAcceleration) {
    notes.push('Includes S3 Transfer Acceleration surcharge on upload volume (~$0.04/GB).');
  }
  return json(200, { estimate, notes });
};
