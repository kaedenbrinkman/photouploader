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
