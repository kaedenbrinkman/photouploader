import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { PhotoPipelineStack } from '../lib/photo-pipeline-stack';

function isAscii(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) > 127) return false;
  }
  return true;
}

describe('PhotoPipelineStack synth', () => {
  it('security group names and descriptions are ASCII-only', () => {
    const app = new cdk.App();
    const stack = new PhotoPipelineStack(app, 'SynthTestStack', {
      env: { account: '123456789012', region: 'us-east-1' },
    });
    const groups = Template.fromStack(stack).findResources('AWS::EC2::SecurityGroup');
    expect(Object.keys(groups).length).toBeGreaterThan(0);
    for (const resource of Object.values(groups)) {
      const props = (resource as { Properties?: Record<string, string> }).Properties ?? {};
      for (const key of ['GroupDescription', 'GroupName'] as const) {
        const value = props[key];
        if (value != null && value !== '') {
          expect(isAscii(value)).toBe(true);
        }
      }
    }
  });

  it('does not create a Cognito user pool (Google session JWT auth only)', () => {
    const app = new cdk.App();
    const stack = new PhotoPipelineStack(app, 'NoCognitoStack', {
      env: { account: '123456789012', region: 'us-east-1' },
    });
    const pools = Template.fromStack(stack).findResources('AWS::Cognito::UserPool');
    expect(Object.keys(pools)).toHaveLength(0);
  });

  it('FinalizeFn has no reserved concurrency by default', () => {
    const app = new cdk.App();
    const stack = new PhotoPipelineStack(app, 'NoReservedStack', {
      env: { account: '123456789012', region: 'us-east-1' },
    });
    const lambdas = Template.fromStack(stack).findResources('AWS::Lambda::Function');
    const finalizeEntries = Object.entries(lambdas).filter(([id]) => id.startsWith('FinalizeFn'));
    expect(finalizeEntries.length).toBe(1);
    const props = (finalizeEntries[0][1] as { Properties?: Record<string, unknown> }).Properties ?? {};
    expect(props.ReservedConcurrentExecutions).toBeUndefined();
  });

  it('FinalizeFn reserved concurrency is opt-in via context', () => {
    const app = new cdk.App({
      context: { finalizeReservedConcurrency: 2 },
    });
    const stack = new PhotoPipelineStack(app, 'ReservedStack', {
      env: { account: '123456789012', region: 'us-east-1' },
    });
    const lambdas = Template.fromStack(stack).findResources('AWS::Lambda::Function');
    const finalizeEntries = Object.entries(lambdas).filter(([id]) => id.startsWith('FinalizeFn'));
    const props = (finalizeEntries[0][1] as { Properties?: Record<string, unknown> }).Properties ?? {};
    expect(props.ReservedConcurrentExecutions).toBe(2);
  });

  it('worker VPC parameters are required at deploy time (no placeholder defaults)', () => {
    const app = new cdk.App();
    const stack = new PhotoPipelineStack(app, 'ParamTestStack', {
      env: { account: '123456789012', region: 'us-east-1' },
    });
    const template = Template.fromStack(stack).toJSON();
    const params = template.Parameters as Record<string, { Default?: string }>;
    expect(params.WorkerVpcId?.Default).toBeUndefined();
    expect(params.WorkerPublicSubnetId?.Default).toBeUndefined();
  });
});
