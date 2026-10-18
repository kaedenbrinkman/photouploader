import { readFileSync } from 'fs';
import { join } from 'path';
import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { EC2Client, RunInstancesCommand } from '@aws-sdk/client-ec2';
import { GetObjectCommand, HeadObjectCommand, S3Client } from '@aws-sdk/client-s3';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { isWorkerInstanceLive } from './ec2-instance';
import {
  ec2ErrorCode,
  ec2ErrorMessage,
  isRetryableEc2LaunchError,
} from './ec2-launch';
import { ManifestValidationError, rawKeyForFile, validateManifest } from './manifest-validate';
import { json, userIdFromEvent } from './http';
import { verifyWorkerArtifact } from './worker-artifacts';
import { loadWorkerSha256FromSsm } from './worker-sha-ssm';

const s3 = new S3Client({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const ec2 = new EC2Client({});

const LAUNCH_LOCK_MS = 5 * 60_000;

const EC2_USERDATA_BOOTSTRAP = readFileSync(
  join(__dirname, 'ec2-userdata-bootstrap.sh'),
  'utf8',
);

interface ManifestFile {
  sha256: string;
  size_bytes: number;
  original_name?: string;
  s3_key?: string;
}

function buildUserData(params: {
  shootId: string;
  userId: string;
  bucket: string;
  table: string;
  tokenParam: string;
  settingsHash: string;
  albumTitle: string;
  bundleKey: string;
  bundleSha256: string;
  lightcraftKey: string;
  lightcraftSha256: string;
}): string {
  const {
    shootId,
    userId,
    bucket,
    table,
    tokenParam,
    settingsHash,
    albumTitle,
    bundleKey,
    bundleSha256,
    lightcraftKey,
    lightcraftSha256,
  } = params;
  const exports = `#!/bin/bash
export SHOOT_ID=${shootId}
export USER_ID=${userId}
export BUCKET_NAME=${bucket}
export DYNAMODB_TABLE=${table}
export GPHOTOS_TOKEN_PARAM=${tokenParam}
export SETTINGS_HASH=${settingsHash}
export ALBUM_TITLE=${JSON.stringify(albumTitle)}
export WORKER_BUNDLE_KEY=${bundleKey}
export WORKER_BUNDLE_SHA256=${bundleSha256}
export LIGHTCRAFT_BINARY_KEY=${lightcraftKey}
export LIGHTCRAFT_BINARY_SHA256=${lightcraftSha256}
export LIGHTCRAFT_BIN=/opt/photopipeline/lightcraft/lightcraft-cli
export INSTALL_ROOT=/opt/photopipeline
`;
  return `${exports}${EC2_USERDATA_BOOTSTRAP}`;
}

async function recordLaunchFailure(
  shootId: string,
  state: string,
  phase: string,
  lastError: string,
  launchErrorCode?: string,
): Promise<void> {
  try {
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
        UpdateExpression:
          'SET #s = :st, phase = :ph, lastError = :e, launchErrorCode = :c, updatedAt = :t',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: {
          ':st': state,
          ':ph': phase,
          ':e': lastError,
          ':c': launchErrorCode ?? null,
          ':t': Date.now(),
        },
      }),
    );
  } catch (writeErr) {
    console.error('recordLaunchFailure dynamodb write failed', writeErr);
  }
}

async function resetStaleProcessing(
  shootId: string,
  reason: string,
): Promise<void> {
  await ddb.send(
    new UpdateCommand({
      TableName: process.env.TABLE_NAME,
      Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
      UpdateExpression:
        'SET #s = :r, phase = :ph, lastError = :e, updatedAt = :t REMOVE instanceId, instanceType, spot, workerStartedAt',
      ExpressionAttributeNames: { '#s': 'state' },
      ExpressionAttributeValues: {
        ':r': 'READY_TO_PROCESS',
        ':ph': 'READY_TO_PROCESS',
        ':e': reason,
        ':t': Date.now(),
      },
    }),
  );
}

/** Single atomic lock + READY_TO_PROCESS immediately before RunInstances. */
async function acquireLaunchLockAndMarkReady(shootId: string): Promise<boolean> {
  const now = Date.now();
  try {
    await ddb.send(
      new UpdateCommand({
        TableName: process.env.TABLE_NAME,
        Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
        UpdateExpression:
          'SET launchLockUntil = :u, #s = :v, phase = :q, verifiedAt = :t REMOVE missingKeys, lastError, launchErrorCode',
        ConditionExpression:
          '(attribute_not_exists(launchLockUntil) OR launchLockUntil < :now) AND #s <> :done',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: {
          ':u': now + LAUNCH_LOCK_MS,
          ':now': now,
          ':v': 'READY_TO_PROCESS',
          ':q': 'READY_TO_PROCESS',
          ':t': now,
          ':done': 'DONE',
        },
      }),
    );
    return true;
  } catch (e) {
    if ((e as { name?: string }).name === 'ConditionalCheckFailedException') return false;
    throw e;
  }
}

async function releaseLaunchLock(shootId: string): Promise<void> {
  await ddb.send(
    new UpdateCommand({
      TableName: process.env.TABLE_NAME,
      Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
      UpdateExpression: 'REMOVE launchLockUntil',
    }),
  );
}

async function ensureArtifactsReady(
  bucket: string,
  bundleKey: string,
  bundleSha256: string,
  lightcraftKey: string,
  lightcraftSha256: string,
): Promise<{ ok: true } | { ok: false; detail: string }> {
  const bundle = await verifyWorkerArtifact(bucket, bundleKey, bundleSha256);
  if (!bundle.ok) return { ok: false, detail: bundle.reason };
  const lc = await verifyWorkerArtifact(bucket, lightcraftKey, lightcraftSha256);
  if (!lc.ok) return { ok: false, detail: lc.reason };
  return { ok: true };
}

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const userId = userIdFromEvent(event);
  const shootId = event.pathParameters?.shootId;
  if (!userId || !shootId) return json(401, { error: 'unauthorized' });

  const meta = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) return json(404, { error: 'shoot not found' });

  if (meta.Item.state === 'DONE') {
    return json(202, { state: 'DONE', instanceId: meta.Item.instanceId });
  }

  if (meta.Item.state === 'PROCESSING') {
    const instanceId = meta.Item.instanceId as string | undefined;
    if (instanceId && (await isWorkerInstanceLive(instanceId))) {
      return json(202, { state: 'PROCESSING', instanceId });
    }
    await resetStaleProcessing(
      shootId,
      instanceId
        ? `Worker instance ${instanceId} is no longer running — ready to relaunch`
        : 'Worker instance missing — ready to relaunch',
    );
  }

  let lockHeld = false;
  try {
    const manifestKey = `shoots/${shootId}/manifest.json`;
    let manifest: {
      shoot_id: string;
      user_id: string;
      files: ManifestFile[];
      settings_hash?: string;
      album_title?: string;
    };
    try {
      const obj = await s3.send(
        new GetObjectCommand({ Bucket: process.env.BUCKET_NAME!, Key: manifestKey }),
      );
      manifest = JSON.parse(await obj.Body!.transformToString());
    } catch {
      return json(400, { error: 'manifest missing on S3' });
    }

    try {
      validateManifest(manifest, shootId, userId);
    } catch (e) {
      if (e instanceof ManifestValidationError) return json(400, { error: e.message });
      throw e;
    }

    const missing: string[] = [];
    for (const f of manifest.files ?? []) {
      const key = rawKeyForFile(shootId, f);
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

    const bundleKey = process.env.WORKER_BUNDLE_KEY ?? 'releases/photopipeline-worker.tar.gz';
    const lightcraftKey = process.env.LIGHTCRAFT_BINARY_KEY ?? 'releases/lightcraft-cli';
    const bucket = process.env.BUCKET_NAME!;

    let bundleSha256: string;
    let lightcraftSha256: string;
    try {
      const sha = await loadWorkerSha256FromSsm();
      bundleSha256 = sha.bundleSha256;
      lightcraftSha256 = sha.lightcraftSha256;
    } catch (e) {
      const detail = ec2ErrorMessage(e);
      await recordLaunchFailure(shootId, 'READY_TO_PROCESS', 'READY_TO_PROCESS', detail, 'worker_sha_ssm_missing');
      return json(503, {
        error: 'worker_artifacts_not_configured',
        detail,
        state: 'READY_TO_PROCESS',
      });
    }

    const artifacts = await ensureArtifactsReady(
      bucket,
      bundleKey,
      bundleSha256,
      lightcraftKey,
      lightcraftSha256,
    );
    if (!artifacts.ok) {
      await recordLaunchFailure(
        shootId,
        'READY_TO_PROCESS',
        'READY_TO_PROCESS',
        artifacts.detail,
        'worker_artifacts_missing',
      );
      return json(503, {
        error: 'worker_artifacts_missing',
        detail: artifacts.detail,
        state: 'READY_TO_PROCESS',
      });
    }

    if (!(await acquireLaunchLockAndMarkReady(shootId))) {
      return json(409, { error: 'finalize_in_progress', state: meta.Item.state ?? 'READY_TO_PROCESS' });
    }
    lockHeld = true;

    const settingsHash = manifest.settings_hash ?? meta.Item.settingsHash ?? 'develop.auto-longEdge0-q90';
    const albumTitle = manifest.album_title ?? meta.Item.albumTitle ?? shootId;
    const tokenParam = `/photopipeline/users/${userId}/gphotos-token`;

    const userData = buildUserData({
      shootId,
      userId,
      bucket,
      table: process.env.TABLE_NAME!,
      tokenParam,
      settingsHash,
      albumTitle,
      bundleKey,
      bundleSha256,
      lightcraftKey,
      lightcraftSha256,
    });

    const attempts: Array<{ spot: boolean; type: string }> = [
      { spot: true, type: 'c7i.4xlarge' },
      { spot: true, type: 'c7i.2xlarge' },
      { spot: false, type: 'c7i.4xlarge' },
      { spot: false, type: 'c7i.2xlarge' },
    ];

    let lastError: unknown;
    let lastCode: string | undefined;
    for (const attempt of attempts) {
      console.log(
        JSON.stringify({
          msg: 'finalize launch attempt',
          shootId,
          spot: attempt.spot,
          instanceType: attempt.type,
        }),
      );
      try {
        const run = await ec2.send(
          new RunInstancesCommand({
            ImageId: process.env.WORKER_AMI_ID!,
            MinCount: 1,
            MaxCount: 1,
            InstanceType: attempt.type,
            IamInstanceProfile: { Name: process.env.WORKER_INSTANCE_PROFILE_NAME! },
            InstanceInitiatedShutdownBehavior: 'terminate',
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
              'SET #s = :p, phase = :st, instanceId = :i, instanceType = :it, spot = :sp, workerStartedAt = :t REMOVE lastError, launchErrorCode',
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
        lastCode = ec2ErrorCode(e);
        console.error(
          JSON.stringify({
            msg: 'finalize launch attempt failed',
            shootId,
            spot: attempt.spot,
            instanceType: attempt.type,
            ec2ErrorCode: lastCode,
            error: ec2ErrorMessage(e),
          }),
        );
      }
    }

    const retryable = isRetryableEc2LaunchError(lastError);
    const detail = ec2ErrorMessage(lastError);
    const code = lastCode ?? ec2ErrorCode(lastError);
    const failState = retryable ? 'READY_TO_PROCESS' : 'FAILED';
    const failPhase = retryable ? 'READY_TO_PROCESS' : 'FAILED';

    await recordLaunchFailure(shootId, failState, failPhase, detail, code);

    if (retryable) {
      return json(503, {
        error: 'failed_to_launch_worker',
        ec2ErrorCode: code,
        detail,
        state: 'READY_TO_PROCESS',
      });
    }
    return json(500, { error: 'failed_to_launch_worker', ec2ErrorCode: code, detail, state: 'FAILED' });
  } finally {
    if (lockHeld) {
      await releaseLaunchLock(shootId);
    }
  }
};
