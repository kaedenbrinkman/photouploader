import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, QueryCommand } from '@aws-sdk/lib-dynamodb';
import { GetObjectCommand, S3Client } from '@aws-sdk/client-s3';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { actualComputeCost } from './pricing';
import { json, userIdFromEvent } from './http';
import { albumOpenUrl, mergeProgress, runningActualCompute } from './status-progress';

const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const s3 = new S3Client({});

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const userId = userIdFromEvent(event);
  const shootId = event.pathParameters?.shootId;
  if (!userId || !shootId) return json(401, { error: 'unauthorized' });

  const meta = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) return json(404, { error: 'not found' });

  const filesRes = await ddb.send(
    new QueryCommand({
      TableName: process.env.TABLE_NAME,
      KeyConditionExpression: 'pk = :pk AND begins_with(sk, :f)',
      ExpressionAttributeValues: { ':pk': `SHOOT#${shootId}`, ':f': 'FILE#' },
    }),
  );
  const files = (filesRes.Items ?? []).map((it) => ({
    sha256: it.sha256,
    state: it.state,
    error: it.error,
    originalName: it.originalName,
    sizeBytes: it.sizeBytes,
    mediaType: it.mediaType,
    contentSha256: it.contentSha256 as string | undefined,
    updatedAt: it.updatedAt,
  }));

  let results: unknown = null;
  const key = meta.Item.resultsS3Key ?? `shoots/${shootId}/results.json`;
  try {
    const obj = await s3.send(new GetObjectCommand({ Bucket: process.env.BUCKET_NAME!, Key: key }));
    results = JSON.parse(await obj.Body!.transformToString());
  } catch {
    /* optional */
  }

  const started = meta.Item.workerStartedAt as number | undefined;
  const finished = meta.Item.workerFinishedAt as number | undefined;
  const actualCompute = runningActualCompute(
    started,
    finished,
    meta.Item.instanceType as string | undefined,
    meta.Item.spot as boolean | undefined,
    Date.now(),
    actualComputeCost,
  );

  const progress = mergeProgress(files, {
    totalFiles: meta.Item.totalFiles as number | undefined,
    renderedCount: meta.Item.renderedCount as number | undefined,
    publishedCount: meta.Item.publishedCount as number | undefined,
    photoCount: meta.Item.photoCount as number | undefined,
    videoCount: meta.Item.videoCount as number | undefined,
    renderedPhotoCount: meta.Item.renderedPhotoCount as number | undefined,
    publishedPhotoCount: meta.Item.publishedPhotoCount as number | undefined,
    publishedVideoCount: meta.Item.publishedVideoCount as number | undefined,
  });

  const albumId = meta.Item.albumId as string | undefined;
  const albumProductUrl = meta.Item.albumProductUrl as string | undefined;

  return json(200, {
    shootId,
    state: meta.Item.state,
    phase: meta.Item.phase,
    lastError: meta.Item.lastError,
    launchErrorCode: meta.Item.launchErrorCode,
    updatedAt: meta.Item.updatedAt,
    albumId,
    albumProductUrl: albumOpenUrl(albumId, albumProductUrl),
    instanceId: meta.Item.instanceId,
    instanceType: meta.Item.instanceType,
    spot: meta.Item.spot,
    missingKeys: meta.Item.missingKeys,
    progress,
    estimateAtSubmit: meta.Item.estimateAtSubmit,
    actualCompute,
    workerStartedAt: started,
    renderStartedAt: meta.Item.renderStartedAt as number | undefined,
    maxRuntimeMinutes: meta.Item.maxRuntimeMinutes,
    deadlineAt: meta.Item.deadlineAt,
    files,
    results,
  });
};
