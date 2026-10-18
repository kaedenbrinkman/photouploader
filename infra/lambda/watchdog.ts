import { EC2Client, DescribeInstancesCommand, TerminateInstancesCommand } from '@aws-sdk/client-ec2';
import type { ScheduledHandler } from 'aws-lambda';

const ec2 = new EC2Client({});

export const handler: ScheduledHandler = async () => {
  const maxMinutes = Number(process.env.WORKER_MAX_LIFETIME_MINUTES ?? 120);
  const now = Date.now();
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
  console.log(JSON.stringify({ terminated: toTerminate }));
};
