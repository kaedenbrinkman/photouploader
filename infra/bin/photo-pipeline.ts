#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { PhotoPipelineStack } from '../lib/photo-pipeline-stack';

const app = new cdk.App();
new PhotoPipelineStack(app, 'PhotoPipelineStack', {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region: process.env.CDK_DEFAULT_REGION ?? 'us-west-2',
  },
  description: 'Canon CR3 → S3 → ephemeral LightCraft worker → Google Photos',
});
