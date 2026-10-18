import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { EC2Client, RunInstancesCommand } from '@aws-sdk/client-ec2';
import { GetObjectCommand, HeadObjectCommand, S3Client } from '@aws-sdk/client-s3';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { json, userIdFromEvent } from './http';

const s3 = new S3Client({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const ec2 = new EC2Client({});

interface ManifestFile {
  sha256: string;
  size_bytes: number;
  original_name?: string;
}

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const userId = userIdFromEvent(event);
  const shootId = event.pathParameters?.shootId;
  if (!userId || !shootId) return json(401, { error: 'unauthorized' });

  const meta = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) return json(404, { error: 'shoot not found' });

  const manifestKey = `shoots/${shootId}/manifest.json`;
  let manifest: { files: ManifestFile[]; settings_hash?: string; album_title?: string };
  try {
    const obj = await s3.send(
      new GetObjectCommand({ Bucket: process.env.BUCKET_NAME!, Key: manifestKey }),
    );
    manifest = JSON.parse(await obj.Body!.transformToString());
  } catch {
    return json(400, { error: 'manifest missing on S3' });
  }

  const missing: string[] = [];
  for (const f of manifest.files ?? []) {
    const ext = (f.original_name ?? '').toUpperCase().endsWith('.JPG') ? '.JPG' : '.CR3';
    const key = `shoots/${shootId}/raw/${f.sha256.toLowerCase()}${ext}`;
    try {
      const head = await s3.send(new HeadObjectCommand({ Bucket: process.env.BUCKET_NAME!, Key: key }));
      if (Number(head.ContentLength) !== Number(f.size_bytes)) missing.push(key);
    } catch {
      missing.push(key);
    }
  }

  if (missing.length) {
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
        UpdateExpression: 'SET #s = :u, phase = :ph, missingKeys = :m, updatedAt = :t',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: { ':u': 'UPLOADING', ':ph': 'UPLOAD', ':m': missing, ':t': Date.now() },
      }),
    );
    return json(409, { state: 'UPLOADING', missing });
  }

  await ddb.send(
    new UpdateCommand({
      TableName: process.env.TABLE_NAME,
      Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
      UpdateExpression: 'SET #s = :v, phase = :q, verifiedAt = :t REMOVE missingKeys',
      ConditionExpression: '#s <> :proc AND #s <> :done',
      ExpressionAttributeNames: { '#s': 'state' },
      ExpressionAttributeValues: {
        ':v': 'QUEUED',
        ':q': 'QUEUED',
        ':t': Date.now(),
        ':proc': 'PROCESSING',
        ':done': 'DONE',
      },
    }),
  );

  const settingsHash = manifest.settings_hash ?? meta.Item.settingsHash ?? 'develop.auto-longEdge0-q90';
  const albumTitle = manifest.album_title ?? meta.Item.albumTitle ?? shootId;
  const tokenParam = `/photopipeline/users/${userId}/gphotos-token`;

  const userData = `#!/bin/bash
set -euo pipefail
export SHOOT_ID=${shootId}
export USER_ID=${userId}
export BUCKET_NAME=${process.env.BUCKET_NAME}
export DYNAMODB_TABLE=${process.env.TABLE_NAME}
export GPHOTOS_TOKEN_PARAM=${tokenParam}
export SETTINGS_HASH=${settingsHash}
export ALBUM_TITLE=${JSON.stringify(albumTitle)}
cd /opt/photopipeline/worker && python3 -m worker.main
`;

  const attempts: Array<{ spot: boolean; type: string }> = [
    { spot: true, type: 'c7i.4xlarge' },
    { spot: true, type: 'c7i.2xlarge' },
    { spot: false, type: 'c7i.4xlarge' },
    { spot: false, type: 'c7i.2xlarge' },
  ];

  let lastError: unknown;
  for (const attempt of attempts) {
    try {
      const run = await ec2.send(
        new RunInstancesCommand({
          ImageId: process.env.WORKER_AMI_ID!,
          MinCount: 1,
          MaxCount: 1,
          InstanceType: attempt.type,
          IamInstanceProfile: { Name: process.env.WORKER_INSTANCE_PROFILE_NAME! },
          InstanceMarketOptions: attempt.spot
            ? {
                MarketType: 'spot',
                SpotOptions: { SpotInstanceType: 'one-time', InstanceInterruptionBehavior: 'terminate' },
              }
            : undefined,
          NetworkInterfaces: [
            {
              DeviceIndex: 0,
              SubnetId: process.env.WORKER_SUBNET_ID!,
              Groups: [process.env.WORKER_SECURITY_GROUP_ID!],
              AssociatePublicIpAddress: true,
            },
          ],
          UserData: Buffer.from(userData).toString('base64'),
          TagSpecifications: [
            {
              ResourceType: 'instance',
              Tags: [
                { Key: 'Project', Value: 'PhotoPipeline' },
                { Key: 'ShootId', Value: shootId },
                { Key: 'MaxLifetimeMinutes', Value: process.env.WORKER_MAX_LIFETIME_MINUTES ?? '120' },
              ],
            },
          ],
        }),
      );
      const instanceId = run.Instances?.[0]?.InstanceId;
      await ddb.send(
        new UpdateCommand({
          TableName: process.env.TABLE_NAME,
          Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
          UpdateExpression:
            'SET #s = :p, phase = :st, instanceId = :i, instanceType = :it, spot = :sp, workerStartedAt = :t',
          ExpressionAttributeNames: { '#s': 'state' },
          ExpressionAttributeValues: {
            ':p': 'PROCESSING',
            ':st': 'INSTANCE_STARTING',
            ':i': instanceId,
            ':it': attempt.type,
            ':sp': attempt.spot,
            ':t': Date.now(),
          },
        }),
      );
      return json(202, { state: 'PROCESSING', instanceId, instanceType: attempt.type, spot: attempt.spot });
    } catch (e) {
      lastError = e;
    }
  }

  await ddb.send(
    new UpdateCommand({
      TableName: process.env.TABLE_NAME,
      Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
      UpdateExpression: 'SET #s = :f, phase = :ph, error = :e, updatedAt = :t',
      ExpressionAttributeNames: { '#s': 'state' },
      ExpressionAttributeValues: { ':f': 'FAILED', ':ph': 'FAILED', ':e': String(lastError), ':t': Date.now() },
    }),
  );
  return json(500, { error: 'failed to launch worker', detail: String(lastError) });
};
