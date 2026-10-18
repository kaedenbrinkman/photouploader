import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, GetCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { EC2Client, RunInstancesCommand } from '@aws-sdk/client-ec2';
import { GetObjectCommand, HeadObjectCommand, S3Client } from '@aws-sdk/client-s3';
import type { APIGatewayProxyHandlerV2 } from 'aws-lambda';
import {
  ec2ErrorCode,
  ec2ErrorMessage,
  isRetryableEc2LaunchError,
} from './ec2-launch';
import { ManifestValidationError, rawKeyForFile, validateManifest } from './manifest-validate';
import { json, userIdFromEvent } from './http';

const s3 = new S3Client({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const ec2 = new EC2Client({});

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
  return `#!/bin/bash
set -euo pipefail
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
REGION=$(curl -sf http://169.254.169.254/latest/meta-data/placement/region)
export AWS_DEFAULT_REGION=$REGION
report_fail() {
  export BOOT_MSG="$1"
  python3 - <<'PY' || true
import boto3, os, time
ddb = boto3.client("dynamodb", region_name=os.environ["AWS_DEFAULT_REGION"])
ddb.update_item(
    TableName=os.environ["DYNAMODB_TABLE"],
    Key={"pk": {"S": f"SHOOT#{os.environ['SHOOT_ID']}"}, "sk": {"S": "META"}},
    UpdateExpression="SET #s = :f, phase = :ph, lastError = :e, updatedAt = :t",
    ExpressionAttributeNames={"#s": "state"},
    ExpressionAttributeValues={
        ":f": {"S": "FAILED"},
        ":ph": {"S": "BOOTSTRAP_FAILED"},
        ":e": {"S": os.environ.get("BOOT_MSG", "bootstrap failed")},
        ":t": {"N": str(int(time.time() * 1000))},
    },
)
PY
  exit 1
}
dnf install -y aws-cli python3 python3-pip tar 2>/dev/null || yum install -y aws-cli python3 python3-pip tar
python3 -m pip install --no-cache-dir boto3
mkdir -p "$INSTALL_ROOT"
BUNDLE=/tmp/worker-bundle.tar.gz
aws s3 cp "s3://$BUCKET_NAME/$WORKER_BUNDLE_KEY" "$BUNDLE" || report_fail "worker bundle missing"
echo "$WORKER_BUNDLE_SHA256  $BUNDLE" | sha256sum -c - || report_fail "worker bundle checksum mismatch"
tar -xzf "$BUNDLE" -C "$INSTALL_ROOT"
python3 -m pip install --no-cache-dir -r "$INSTALL_ROOT/photopipeline-worker/requirements.txt"
LC=/tmp/lightcraft-cli
aws s3 cp "s3://$BUCKET_NAME/$LIGHTCRAFT_BINARY_KEY" "$LC" || report_fail "lightcraft binary missing"
echo "$LIGHTCRAFT_BINARY_SHA256  $LC" | sha256sum -c - || report_fail "lightcraft checksum mismatch"
chmod +x "$LC"
mkdir -p "$INSTALL_ROOT/lightcraft"
mv "$LC" "$LIGHTCRAFT_BIN"
cd "$INSTALL_ROOT/photopipeline-worker" && python3 -m worker.main
`;
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

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const userId = userIdFromEvent(event);
  const shootId = event.pathParameters?.shootId;
  if (!userId || !shootId) return json(401, { error: 'unauthorized' });

  const meta = await ddb.send(
    new GetCommand({ TableName: process.env.TABLE_NAME, Key: { pk: `SHOOT#${shootId}`, sk: 'META' } }),
  );
  if (!meta.Item || meta.Item.userId !== userId) return json(404, { error: 'shoot not found' });

  if (meta.Item.state === 'PROCESSING' || meta.Item.state === 'DONE') {
    return json(202, { state: meta.Item.state, instanceId: meta.Item.instanceId });
  }

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
  const bundleSha256 = process.env.WORKER_BUNDLE_SHA256 ?? '';
  const lightcraftKey = process.env.LIGHTCRAFT_BINARY_KEY ?? 'releases/lightcraft-cli';
  const lightcraftSha256 = process.env.LIGHTCRAFT_BINARY_SHA256 ?? '';
  if (!bundleSha256 || !lightcraftSha256) {
    return json(503, {
      error: 'worker_artifacts_not_configured',
      detail: 'WORKER_BUNDLE_SHA256 and LIGHTCRAFT_BINARY_SHA256 must be set on finalize Lambda',
      state: 'READY_TO_PROCESS',
    });
  }

  await ddb.send(
    new UpdateCommand({
      TableName: process.env.TABLE_NAME,
      Key: { pk: `SHOOT#${shootId}`, sk: 'META' },
      UpdateExpression: 'SET #s = :v, phase = :q, verifiedAt = :t REMOVE missingKeys, lastError, launchErrorCode',
      ConditionExpression: '#s <> :proc AND #s <> :done',
      ExpressionAttributeNames: { '#s': 'state' },
      ExpressionAttributeValues: {
        ':v': 'READY_TO_PROCESS',
        ':q': 'READY_TO_PROCESS',
        ':t': Date.now(),
        ':proc': 'PROCESSING',
        ':done': 'DONE',
      },
    }),
  );

  const settingsHash = manifest.settings_hash ?? meta.Item.settingsHash ?? 'develop.auto-longEdge0-q90';
  const albumTitle = manifest.album_title ?? meta.Item.albumTitle ?? shootId;
  const tokenParam = `/photopipeline/users/${userId}/gphotos-token`;

  const userData = buildUserData({
    shootId,
    userId,
    bucket: process.env.BUCKET_NAME!,
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
};
