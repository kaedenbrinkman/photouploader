import { UpdateCommand, type DynamoDBDocumentClient } from '@aws-sdk/lib-dynamodb';
import { RunInstancesCommand, type EC2Client } from '@aws-sdk/client-ec2';
import { ec2ErrorCode, ec2ErrorMessage, isRetryableEc2LaunchError } from './ec2-launch';
import type { ManifestFile } from './manifest-validate';
import { computeDeadlineAt, computeMaxRuntimeMinutes, publishOnlyInstanceTypeOrder } from './pricing';
import { splitManifestFiles } from './media-kind';
import { computeRootVolumeGiB } from './worker-root-volume';
import { buildUserData, normalizeLinuxLineEndings } from './user-data-build';
import { detectWorkerLaunchProfile } from './worker-launch-profile';
import type { S3Client } from '@aws-sdk/client-s3';
import EC2_USERDATA_BOOTSTRAP from './ec2-userdata-bootstrap.sh';

export interface LaunchWorkerParams {
  shootId: string;
  userId: string;
  manifest: {
    settings_hash?: string;
    album_title?: string;
    files: ManifestFile[];
  };
  meta: Record<string, unknown>;
  bucket: string;
  tableName: string;
  bundleKey: string;
  bundleSha256: string;
  lightcraftKey: string;
  lightcraftSha256: string;
  ddb: DynamoDBDocumentClient;
  ec2: EC2Client;
  s3: S3Client;
  workerAmiId: string;
  workerInstanceProfileName: string;
  workerSubnetId: string;
  workerSecurityGroupId: string;
  tokenParam: string;
}

export type LaunchWorkerResult =
  | { ok: true; instanceId: string; instanceType: string; spot: boolean }
  | { ok: false; retryable: boolean; detail: string; code?: string; failState: string; failPhase: string };

export async function launchWorkerForShoot(p: LaunchWorkerParams): Promise<LaunchWorkerResult> {
  const settingsHash = p.manifest.settings_hash ?? (p.meta.settingsHash as string) ?? 'develop.auto-longEdge0-q90';
  const albumTitle = p.manifest.album_title ?? (p.meta.albumTitle as string) ?? p.shootId;
  const manifestFiles = p.manifest.files ?? [];
  const split = splitManifestFiles(manifestFiles);
  const launchProfile = await detectWorkerLaunchProfile(
    p.s3,
    p.bucket,
    p.shootId,
    settingsHash,
    manifestFiles,
  );
  const publishOnly = launchProfile === 'publish_only';
  const maxRuntimeMinutes = computeMaxRuntimeMinutes(split.photoCount, split.videoBytes, { publishOnly });
  const rootVolumeGiB = computeRootVolumeGiB(manifestFiles);
  const launchAt = Date.now();
  const deadlineAt = computeDeadlineAt(launchAt, maxRuntimeMinutes);

  const userData = buildUserData(
    {
      shootId: p.shootId,
      userId: p.userId,
      bucket: p.bucket,
      table: p.tableName,
      tokenParam: p.tokenParam,
      settingsHash,
      albumTitle,
      bundleKey: p.bundleKey,
      bundleSha256: p.bundleSha256,
      lightcraftKey: p.lightcraftKey,
      lightcraftSha256: p.lightcraftSha256,
      maxRuntimeMinutes,
    },
    normalizeLinuxLineEndings(EC2_USERDATA_BOOTSTRAP),
  );

  const renderAttempts: Array<{ spot: boolean; type: string }> = [
    { spot: true, type: 'c7i.4xlarge' },
    { spot: true, type: 'c7i.2xlarge' },
    { spot: false, type: 'c7i.4xlarge' },
    { spot: false, type: 'c7i.2xlarge' },
  ];
  const publishTypes = publishOnlyInstanceTypeOrder();
  const publishAttempts: Array<{ spot: boolean; type: string }> = publishTypes.flatMap((type) => [
    { spot: true, type },
    { spot: false, type },
  ]);
  const attempts = publishOnly ? publishAttempts : renderAttempts;

  let lastError: unknown;
  let lastCode: string | undefined;
  for (const attempt of attempts) {
    console.log(
      JSON.stringify({
        msg: 'finalize launch attempt',
        shootId: p.shootId,
        spot: attempt.spot,
        instanceType: attempt.type,
        launchProfile,
      }),
    );
    try {
      const run = await p.ec2.send(
        new RunInstancesCommand({
          ImageId: p.workerAmiId,
          MinCount: 1,
          MaxCount: 1,
          InstanceType: attempt.type,
          IamInstanceProfile: { Name: p.workerInstanceProfileName },
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
              SubnetId: p.workerSubnetId,
              Groups: [p.workerSecurityGroupId],
              AssociatePublicIpAddress: true,
            },
          ],
          BlockDeviceMappings: [
            {
              DeviceName: '/dev/xvda',
              Ebs: {
                VolumeSize: rootVolumeGiB,
                VolumeType: 'gp3',
                DeleteOnTermination: true,
              },
            },
          ],
          UserData: Buffer.from(userData).toString('base64'),
          TagSpecifications: [
            {
              ResourceType: 'instance',
              Tags: [
                { Key: 'Project', Value: 'PhotoPipeline' },
                { Key: 'ShootId', Value: p.shootId },
                { Key: 'MaxLifetimeMinutes', Value: String(maxRuntimeMinutes) },
                { Key: 'DeadlineAt', Value: String(deadlineAt) },
              ],
            },
          ],
        }),
      );
      const instanceId = run.Instances?.[0]?.InstanceId;
      await p.ddb.send(
        new UpdateCommand({
          TableName: p.tableName,
          Key: { pk: `SHOOT#${p.shootId}`, sk: 'META' },
          UpdateExpression:
            'SET #s = :p, phase = :st, instanceId = :i, instanceType = :it, spot = :sp, workerStartedAt = :t, maxRuntimeMinutes = :mr, deadlineAt = :dl, launchProfile = :lp, photoCount = :pc, videoCount = :vc, totalFiles = :tf, renderedCount = :rc REMOVE lastError, launchErrorCode, launchLockUntil',
          ExpressionAttributeNames: { '#s': 'state' },
          ExpressionAttributeValues: {
            ':p': 'PROCESSING',
            ':st': 'INSTANCE_STARTING',
            ':i': instanceId,
            ':it': attempt.type,
            ':sp': attempt.spot,
            ':t': launchAt,
            ':mr': maxRuntimeMinutes,
            ':dl': deadlineAt,
            ':lp': launchProfile,
            ':pc': split.photoCount,
            ':vc': split.videoCount,
            ':tf': split.photoCount + split.videoCount,
            ':rc': 0,
          },
        }),
      );
      return { ok: true, instanceId: instanceId!, instanceType: attempt.type, spot: attempt.spot };
    } catch (e) {
      lastError = e;
      lastCode = ec2ErrorCode(e);
      console.error(
        JSON.stringify({
          msg: 'finalize launch attempt failed',
          shootId: p.shootId,
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
  return {
    ok: false,
    retryable,
    detail,
    code,
    failState: retryable ? 'READY_TO_PROCESS' : 'FAILED',
    failPhase: retryable ? 'READY_TO_PROCESS' : 'FAILED',
  };
}
