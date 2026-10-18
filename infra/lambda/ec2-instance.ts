import { DescribeInstancesCommand, EC2Client } from '@aws-sdk/client-ec2';

const ec2 = new EC2Client({});

const LIVE_STATES = new Set(['pending', 'running']);

export async function isWorkerInstanceLive(instanceId: string): Promise<boolean> {
  try {
    const res = await ec2.send(new DescribeInstancesCommand({ InstanceIds: [instanceId] }));
    const state = res.Reservations?.[0]?.Instances?.[0]?.State?.Name;
    return state != null && LIVE_STATES.has(state);
  } catch {
    return false;
  }
}
