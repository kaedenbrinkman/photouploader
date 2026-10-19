import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, QueryCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { EC2Client } from '@aws-sdk/client-ec2';
import { GetObjectCommand, HeadObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { InvokeCommand, LambdaClient } from '@aws-sdk/client-lambda';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import { isWorkerInstanceLive } from './ec2-instance';
import { ec2ErrorMessage } from './ec2-launch';
import { ManifestValidationError, validateManifest } from './manifest-validate';
import { splitManifestFiles } from './media-kind';
import {
  manifestKeysMissingFromDynamo,
  spotCheckManifestOnS3,
  type DynamoManifestFileRow,
} from './manifest-upload-ready';
import { launchWorkerForShoot } from './finalize-launch-worker';
import { json, userIdFromEvent } from './http';
import { verifyWorkerArtifact } from './worker-artifacts';
import { computeDeadlineAt, computeMaxRuntimeMinutes } from './pricing';
import { loadWorkerSha256FromSsm } from './worker-sha-ssm';
import { canReprocessShoot } from './finalize-eligibility';

const s3 = new S3Client({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const ec2 = new EC2Client({});
const lambda = new LambdaClient({});

const LAUNCH_LOCK_MS = 5 * 60_000;

interface ManifestFile {
  sha256: string;
  size_bytes: number;
  original_name?: string;
  s3_key?: string;
}

interface LaunchWorkerPayload {
  launchWorker: true;
  shootId: string;
  userId: string;
}

function isLaunchWorkerEvent(event: unknown): event is LaunchWorkerPayload {
  return Boolean(
    event &&
      typeof event === 'object' &&
      (event as LaunchWorkerPayload).launchWorker === true &&
      typeof (event as LaunchWorkerPayload).shootId === 'string' &&
      typeof (event as LaunchWorkerPayload).userId === 'string',
  );
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

async function resetStaleProcessing(shootId: string, reason: string): Promise<void> {
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

/** Single atomic lock + READY_TO_PROCESS immediately before async worker launch. */
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
          '(attribute_not_exists(launchLockUntil) OR launchLockUntil < :now) AND #s <> :processing',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: {
          ':u': now + LAUNCH_LOCK_MS,
          ':now': now,
          ':v': 'READY_TO_PROCESS',
          ':q': 'LAUNCHING',
          ':t': now,
          ':processing': 'PROCESSING',
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
      UpdateExpression: 'REMOVE launchLockUntil SET phase = :ph',
      ExpressionAttributeValues: { ':ph': 'READY_TO_PROCESS' },
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

async function loadManifest(shootId: string): Promise<
  | {
      ok: true;
      manifest: {
        shoot_id: string;
        user_id: string;
        files: ManifestFile[];
        settings_hash?: string;
        album_title?: string;
      };
    }
  | { ok: false }
> {
  const manifestKey = `shoots/${shootId}/manifest.json`;
  try {
    const obj = await s3.send(
      new GetObjectCommand({ Bucket: process.env.BUCKET_NAME!, Key: manifestKey }),
    );
    const manifest = JSON.parse(await obj.Body!.transformToString());
    return { ok: true, manifest };
  } catch {
    return { ok: false };
  }
}

async function enqueueAsyncLaunch(shootId: string, userId: string): Promise<void> {
  const fn = process.env.AWS_LAMBDA_FUNCTION_NAME;
  if (!fn) throw new Error('AWS_LAMBDA_FUNCTION_NAME not set');
  await lambda.send(
    new InvokeCommand({
      FunctionName: fn,
      InvocationType: 'Event',
      Payload: Buffer.from(JSON.stringify({ launchWorker: true, shootId, userId } satisfies LaunchWorkerPayload)),
    }),
  );
}

async function handleLaunchWorker(payload: LaunchWorkerPayload) {
  const { shootId, userId } = payload;
  const meta = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) {
    console.error(JSON.stringify({ msg: 'launchWorker unauthorized or missing', shootId }));
    return;
  }

  if (meta.Item.state === 'PROCESSING') {
    const instanceId = meta.Item.instanceId as string | undefined;
    if (instanceId && (await isWorkerInstanceLive(instanceId))) {
      return;
    }
  }

  const loaded = await loadManifest(shootId);
  if (!loaded.ok) {
    await recordLaunchFailure(shootId, 'READY_TO_PROCESS', 'READY_TO_PROCESS', 'manifest missing on S3');
    await releaseLaunchLock(shootId);
    return;
  }

  const bucket = process.env.BUCKET_NAME!;
  const bundleKey = process.env.WORKER_BUNDLE_KEY ?? 'releases/photopipeline-worker.tar.gz';
  const lightcraftKey = process.env.LIGHTCRAFT_BINARY_KEY ?? 'releases/lightcraft-cli';

  let bundleSha256: string;
  let lightcraftSha256: string;
  try {
    const sha = await loadWorkerSha256FromSsm();
    bundleSha256 = sha.bundleSha256;
    lightcraftSha256 = sha.lightcraftSha256;
  } catch (e) {
    await recordLaunchFailure(
      shootId,
      'READY_TO_PROCESS',
      'READY_TO_PROCESS',
      ec2ErrorMessage(e),
      'worker_sha_ssm_missing',
    );
    await releaseLaunchLock(shootId);
    return;
  }

  const tokenParam = `/photopipeline/users/${userId}/gphotos-token`;
  const result = await launchWorkerForShoot({
    shootId,
    userId,
    manifest: loaded.manifest,
    meta: meta.Item,
    bucket,
    tableName: process.env.TABLE_NAME!,
    bundleKey,
    bundleSha256,
    lightcraftKey,
    lightcraftSha256,
    ddb,
    ec2,
    workerAmiId: process.env.WORKER_AMI_ID!,
    workerInstanceProfileName: process.env.WORKER_INSTANCE_PROFILE_NAME!,
    workerSubnetId: process.env.WORKER_SUBNET_ID!,
    workerSecurityGroupId: process.env.WORKER_SECURITY_GROUP_ID!,
    tokenParam,
  });

  if (!result.ok) {
    await recordLaunchFailure(shootId, result.failState, result.failPhase, result.detail, result.code);
    await releaseLaunchLock(shootId);
  }
}

async function respondLaunchInProgress(shootId: string) {
  const fresh = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  const state = (fresh.Item?.state as string) ?? 'READY_TO_PROCESS';
  const phase = (fresh.Item?.phase as string) ?? 'LAUNCHING';
  return json(202, {
    state,
    phase,
    instanceId: fresh.Item?.instanceId,
    launchAccepted: true,
  });
}

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  if (isLaunchWorkerEvent(event)) {
    await handleLaunchWorker(event);
    return { statusCode: 200, body: '' };
  }

  const userId = userIdFromEvent(event);
  const shootId = event.pathParameters?.shootId;
  if (!userId || !shootId) return json(401, { error: 'unauthorized' });

  const meta = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) return json(404, { error: 'shoot not found' });

  let forceReprocess = false;
  try {
    const body = event.body ? JSON.parse(event.body) : {};
    forceReprocess = Boolean(body.force);
  } catch {
    forceReprocess = false;
  }

  const fileRowsRes = await ddb.send(
    new QueryCommand({
      TableName: process.env.TABLE_NAME,
      KeyConditionExpression: 'pk = :pk AND begins_with(sk, :f)',
      ExpressionAttributeValues: { ':pk': `SHOOT#${shootId}`, ':f': 'FILE#' },
    }),
  );
  const dynamoFileRows: DynamoManifestFileRow[] = (fileRowsRes.Items ?? []).map((it) => ({
    sha256: it.sha256 as string | undefined,
    state: it.state as string | undefined,
    sizeBytes: it.sizeBytes as number | undefined,
  }));
  const fileRows = dynamoFileRows.map((it) => ({ state: it.state }));

  if (meta.Item.state === 'DONE' && !canReprocessShoot(meta.Item, fileRows, { force: forceReprocess })) {
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

  const phase = meta.Item.phase as string | undefined;
  const lockUntil = meta.Item.launchLockUntil as number | undefined;
  if (phase === 'LAUNCHING' && lockUntil != null && lockUntil > Date.now()) {
    return respondLaunchInProgress(shootId);
  }

  let lockHeld = false;
  let releaseLockAfter = false;
  try {
    const loaded = await loadManifest(shootId);
    if (!loaded.ok) return json(400, { error: 'manifest missing on S3' });
    const manifest = loaded.manifest;

    try {
      validateManifest(manifest, shootId, userId);
    } catch (e) {
      if (e instanceof ManifestValidationError) return json(400, { error: e.message });
      throw e;
    }

    const missingFromDynamo = manifestKeysMissingFromDynamo(shootId, manifest.files ?? [], dynamoFileRows);
    if (missingFromDynamo.length) {
      await ddb.send(
        new UpdateCommand({
          TableName: process.env.TABLE_NAME,
          Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
          UpdateExpression: 'SET #s = :u, phase = :ph, missingKeys = :m, updatedAt = :t',
          ExpressionAttributeNames: { '#s': 'state' },
          ExpressionAttributeValues: {
            ':u': 'UPLOADING',
            ':ph': 'UPLOAD',
            ':m': missingFromDynamo,
            ':t': Date.now(),
          },
        }),
      );
      return json(409, { state: 'UPLOADING', missing: missingFromDynamo });
    }

    const bucket = process.env.BUCKET_NAME!;
    const spotMissing = await spotCheckManifestOnS3(manifest.files ?? [], shootId, async (key) => {
      try {
        const head = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
        return head.ContentLength ?? null;
      } catch {
        return null;
      }
    });
    if (spotMissing.length) {
      await ddb.send(
        new UpdateCommand({
          TableName: process.env.TABLE_NAME,
          Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
          UpdateExpression: 'SET #s = :u, phase = :ph, missingKeys = :m, updatedAt = :t',
          ExpressionAttributeNames: { '#s': 'state' },
          ExpressionAttributeValues: {
            ':u': 'UPLOADING',
            ':ph': 'UPLOAD',
            ':m': spotMissing,
            ':t': Date.now(),
          },
        }),
      );
      return json(409, { state: 'UPLOADING', missing: spotMissing });
    }

    const bundleKey = process.env.WORKER_BUNDLE_KEY ?? 'releases/photopipeline-worker.tar.gz';
    const lightcraftKey = process.env.LIGHTCRAFT_BINARY_KEY ?? 'releases/lightcraft-cli';

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
      return respondLaunchInProgress(shootId);
    }
    lockHeld = true;

    try {
      await enqueueAsyncLaunch(shootId, userId);
    } catch (e) {
      releaseLockAfter = true;
      const detail = ec2ErrorMessage(e);
      await recordLaunchFailure(shootId, 'READY_TO_PROCESS', 'READY_TO_PROCESS', detail, 'launch_enqueue_failed');
      return json(503, { error: 'failed_to_launch_worker', detail, state: 'READY_TO_PROCESS' });
    }

    lockHeld = false;
    const split = splitManifestFiles(manifest.files ?? []);
    const maxRuntimeMinutes = computeMaxRuntimeMinutes(split.photoCount, split.videoBytes);
    const launchAt = Date.now();
    return json(202, {
      state: 'READY_TO_PROCESS',
      phase: 'LAUNCHING',
      launchAccepted: true,
      maxRuntimeMinutes,
      deadlineAt: computeDeadlineAt(launchAt, maxRuntimeMinutes),
    });
  } finally {
    if (lockHeld && releaseLockAfter) {
      await releaseLaunchLock(shootId);
    }
  }
};
