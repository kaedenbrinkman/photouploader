import { DynamoDBClient } from '@aws-sdk/client-dynamodb';
import { DynamoDBDocumentClient, ScanCommand, UpdateCommand } from '@aws-sdk/lib-dynamodb';
import { EC2Client, DescribeInstancesCommand, TerminateInstancesCommand } from '@aws-sdk/client-ec2';
import type { ScheduledHandler } from 'aws-lambda';
import { isWorkerInstanceLive } from './ec2-instance';

const ec2 = new EC2Client({});
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));

export const handler: ScheduledHandler = async () => {
  const maxMinutes = Number(process.env.WORKER_MAX_LIFETIME_MINUTES ?? 120);
  const now = Date.now();
  const table = process.env.TABLE_NAME;
  if (!table) throw new Error('TABLE_NAME required');

  const res = await ec2.send(
    new DescribeInstancesCommand({
      Filters: [
        { Name: 'tag:Project', Values: ['PhotoPipeline'] },
        { Name: 'instance-state-name', Values: ['running', 'pending'] },
      ],
    }),
  );
  const toTerminate: string[] = [];
  for (const r of res.Reservations ?? []) {
    for (const i of r.Instances ?? []) {
      const launch = i.LaunchTime?.getTime() ?? now;
      const tagMax = i.Tags?.find((t) => t.Key === 'MaxLifetimeMinutes')?.Value;
      const limitMs = (tagMax ? Number(tagMax) : maxMinutes) * 60_000;
      if (now - launch > limitMs) toTerminate.push(i.InstanceId!);
    }
  }
  if (toTerminate.length) {
    await ec2.send(new TerminateInstancesCommand({ InstanceIds: toTerminate }));
  }

  const staleResets: string[] = [];
  let lastKey: Record<string, unknown> | undefined;
  do {
    const scan = await ddb.send(
      new ScanCommand({
        TableName: table,
        FilterExpression: 'begins_with(pk, :p) AND sk = :meta AND #s = :proc',
        ExpressionAttributeNames: { '#s': 'state' },
        ExpressionAttributeValues: {
          ':p': 'SHOOT#',
          ':meta': 'META',
          ':proc': 'PROCESSING',
        },
        ExclusiveStartKey: lastKey,
      }),
    );
    for (const item of scan.Items ?? []) {
      const shootId = String(item.pk).replace(/^SHOOT#/, '');
      const instanceId = item.instanceId as string | undefined;
      if (instanceId && (await isWorkerInstanceLive(instanceId))) continue;
      await ddb.send(
        new UpdateCommand({
          TableName: table,
          Key: { pk: item.pk, sk: item.sk },
          UpdateExpression:
            'SET #s = :r, phase = :ph, lastError = :e, updatedAt = :t REMOVE instanceId, instanceType, spot, workerStartedAt, launchLockUntil',
          ExpressionAttributeNames: { '#s': 'state' },
          ExpressionAttributeValues: {
            ':r': 'READY_TO_PROCESS',
            ':ph': 'READY_TO_PROCESS',
            ':e': instanceId
              ? `Worker instance ${instanceId} stopped — ready to relaunch`
              : 'Worker instance missing — ready to relaunch',
            ':t': Date.now(),
          },
        }),
      );
      staleResets.push(shootId);
    }
    lastKey = scan.LastEvaluatedKey;
  } while (lastKey);

  console.log(JSON.stringify({ terminated: toTerminate, staleResets }));
};
