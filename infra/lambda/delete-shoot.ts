import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import {
  BatchWriteCommand,
  DynamoDBDocumentClient,
  GetCommand,
  QueryCommand,
} from '@aws-sdk/lib-dynamodb';
import {
  DeleteObjectsCommand,
  ListObjectsV2Command,
  ListMultipartUploadsCommand,
  AbortMultipartUploadCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { json, userIdFromEvent } from './http';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const s3 = new S3Client({});

async function deleteS3Prefix(bucket: string, prefix: string) {
  let token: string | undefined;
  do {
    const listed = await s3.send(
      new ListObjectsV2Command({ Bucket: bucket, Prefix: prefix, ContinuationToken: token }),
    );
    const keys = (listed.Contents ?? []).map((o) => ({ Key: o.Key! }));
    if (keys.length > 0) {
      await s3.send(new DeleteObjectsCommand({ Bucket: bucket, Delete: { Objects: keys } }));
    }
    token = listed.IsTruncated ? listed.NextContinuationToken : undefined;
  } while (token);

  let uploadToken: string | undefined;
  do {
    const uploads = await s3.send(
      new ListMultipartUploadsCommand({
        Bucket: bucket,
        Prefix: prefix,
        KeyMarker: uploadToken,
      }),
    );
    for (const u of uploads.Uploads ?? []) {
      if (!u.Key || !u.UploadId) continue;
      await s3.send(
        new AbortMultipartUploadCommand({
          Bucket: bucket,
          Key: u.Key,
          UploadId: u.UploadId,
        }),
      );
    }
    uploadToken = uploads.IsTruncated ? uploads.NextKeyMarker : undefined;
  } while (uploadToken);
}

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const userId = userIdFromEvent(event);
  const shootId = event.pathParameters?.shootId;
  const bucket = process.env.BUCKET_NAME;
  const table = process.env.TABLE_NAME;
  if (!userId || !shootId || !bucket || !table) return json(401, { error: 'unauthorized' });

  const meta = await ddb.send(
    new GetCommand({ TableName: table, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) return json(404, { error: 'not found' });

  await deleteS3Prefix(bucket, `shoots/${shootId}/`);

  const pk = `SHOOT#${shootId}`;
  let startKey: Record<string, unknown> | undefined;
  do {
    const res = await ddb.send(
      new QueryCommand({
        TableName: table,
        KeyConditionExpression: 'pk = :pk',
        ExpressionAttributeValues: { ':pk': pk },
        ExclusiveStartKey: startKey,
      }),
    );
    const items = res.Items ?? [];
    for (let i = 0; i < items.length; i += 25) {
      const chunk = items.slice(i, i + 25);
      await ddb.send(
        new BatchWriteCommand({
          RequestItems: {
            [table]: chunk.map((it) => ({
              DeleteRequest: { Key: { pk: it.pk, sk: it.sk } },
            })),
          },
        }),
      );
    }
    startKey = res.LastEvaluatedKey;
  } while (startKey);

  return json(200, { deleted: true, shootId });
};
