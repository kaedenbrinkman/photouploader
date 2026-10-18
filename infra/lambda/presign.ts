import { ConditionalCheckFailedException, DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, PutCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import {
  CompleteMultipartUploadCommand,
  CreateMultipartUploadCommand,
  ListPartsCommand,
  PutObjectCommand,
  S3Client,
  UploadPartCommand,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { randomUUID } from 'crypto';
import { json, userIdFromEvent } from './http';

const s3Standard = new S3Client({});
const s3Accelerated = new S3Client({ useAccelerateEndpoint: true });
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

function wantsTransferAcceleration(body: Record<string, unknown>): boolean {
  return Boolean(body.transferAcceleration);
}

function s3For(body: Record<string, unknown>): S3Client {
  return wantsTransferAcceleration(body) ? s3Accelerated : s3Standard;
}

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const userId = userIdFromEvent(event);
  if (!userId) return json(401, { error: 'unauthorized' });

  const body = event.body ? JSON.parse(event.body) : {};
  const shootId = event.pathParameters?.shootId ?? body.shootId ?? randomUUID();
  const action = body.action ?? 'create_shoot';
  const accelerated = wantsTransferAcceleration(body);

  if (action === 'create_shoot') {
    try {
      await ddb.send(
        new PutCommand({
          TableName: process.env.TABLE_NAME,
          Item: {
            pk: `SHOOT#${shootId}`,
            sk: 'META',
            shootId,
            userId,
            state: 'UPLOADING',
            phase: 'UPLOAD',
            albumTitle: body.albumTitle ?? shootId,
            settingsHash: body.settingsHash ?? 'develop.auto-longEdge0-q90',
            createdAt: Date.now(),
          },
          ConditionExpression: 'attribute_not_exists(pk)',
        }),
      );
    } catch (e) {
      if (!(e instanceof ConditionalCheckFailedException)) throw e;
      const existing = await ddb.send(
        new GetCommand({
          TableName: process.env.TABLE_NAME,
          Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
        }),
      );
      if (!existing.Item || existing.Item.userId !== userId) {
        return json(409, { error: 'shoot_exists', shootId });
      }
    }
    return json(200, { shootId, state: 'UPLOADING' });
  }

  if (action === 'presign_part') {
    const { sha256, partNumber, uploadId, originalName, sizeBytes } = body;
    if (!sha256 || !partNumber || !uploadId) return json(400, { error: 'missing fields' });
    const ext = (originalName as string | undefined)?.toUpperCase().endsWith('.JPG') ? '.JPG' : '.CR3';
    const key = `shoots/${shootId}/raw/${String(sha256).toLowerCase()}${ext}`;
    const url = await getSignedUrl(
      s3For(body),
      new UploadPartCommand({
        Bucket: process.env.BUCKET_NAME,
        Key: key,
        UploadId: uploadId,
        PartNumber: Number(partNumber),
      }),
      { expiresIn: 3600 },
    );
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `SHOOT#${shootId}`, sk: `FILE#${String(sha256).toLowerCase()}` },
        UpdateExpression:
          'SET #s = if_not_exists(#s, :uploading), sha256 = :sha, sizeBytes = :sz, originalName = :name, updatedAt = :u',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: {
          ':uploading': 'UPLOADING',
          ':sha': String(sha256).toLowerCase(),
          ':sz': Number(sizeBytes ?? 0),
          ':name': originalName ?? 'unknown.cr3',
          ':u': Date.now(),
        },
      }),
    );
    return json(200, { url, key, partNumber, uploadId, transferAcceleration: accelerated });
  }

  if (action === 'list_parts') {
    const { uploadId, key } = body;
    if (!uploadId || !key) return json(400, { error: 'missing fields' });
    const listed = await s3For(body).send(
      new ListPartsCommand({ Bucket: process.env.BUCKET_NAME!, Key: key, UploadId: uploadId }),
    );
    const parts = (listed.Parts ?? []).map((p) => ({
      partNumber: p.PartNumber,
      eTag: p.ETag?.replace(/"/g, '') ?? '',
    }));
    return json(200, { parts, transferAcceleration: accelerated });
  }

  if (action === 'presign_manifest') {
    const key = `shoots/${shootId}/manifest.json`;
    const url = await getSignedUrl(
      s3For(body),
      new PutObjectCommand({
        Bucket: process.env.BUCKET_NAME!,
        Key: key,
        ContentType: 'application/json',
      }),
      { expiresIn: 3600 },
    );
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
        UpdateExpression: 'SET manifestExpected = :t, updatedAt = :u',
        ExpressionAttributeValues: { ':t': true, ':u': Date.now() },
      }),
    );
    return json(200, { url, key, transferAcceleration: accelerated });
  }

  if (action === 'complete_multipart') {
    const { key, uploadId, parts } = body;
    if (!key || !uploadId || !parts) return json(400, { error: 'missing fields' });
    await s3For(body).send(
      new CompleteMultipartUploadCommand({
        Bucket: process.env.BUCKET_NAME!,
        Key: key,
        UploadId: uploadId,
        MultipartUpload: { Parts: parts },
      }),
    );
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `SHOOT#${shootId}`, sk: `FILE#${String(body.sha256).toLowerCase()}` },
        UpdateExpression: 'SET #s = :u, updatedAt = :t',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: { ':u': 'UPLOADED', ':t': Date.now() },
      }),
    );
    return json(200, { key, completed: true, transferAcceleration: accelerated });
  }

  if (action === 'create_multipart') {
    const { sha256, originalName } = body;
    const ext = (originalName as string | undefined)?.toUpperCase().endsWith('.JPG') ? '.JPG' : '.CR3';
    const key = `shoots/${shootId}/raw/${String(sha256).toLowerCase()}${ext}`;
    const created = await s3For(body).send(
      new CreateMultipartUploadCommand({
        Bucket: process.env.BUCKET_NAME!,
        Key: key,
        ContentType: 'application/octet-stream',
      }),
    );
    return json(200, { uploadId: created.UploadId, key, transferAcceleration: accelerated });
  }

  return json(400, { error: 'unknown action' });
};
