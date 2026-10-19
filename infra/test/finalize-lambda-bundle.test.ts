import * as cdk from 'aws-cdk-lib';
import * as fs from 'fs';
import * as path from 'path';
import { PhotoPipelineStack } from '../lib/photo-pipeline-stack';

const SYNTH_OUT = path.join(__dirname, '../cdk.out.finalize-bundle-test');
const FINALIZE_MARKER = 'worker_artifacts_missing';

function findFinalizeAssetIndexJs(): string {
  if (!fs.existsSync(SYNTH_OUT)) {
    throw new Error(`missing synth output: ${SYNTH_OUT}`);
  }
  for (const name of fs.readdirSync(SYNTH_OUT)) {
    if (!name.startsWith('asset.')) continue;
    const indexJs = path.join(SYNTH_OUT, name, 'index.js');
    if (!fs.existsSync(indexJs)) continue;
    const src = fs.readFileSync(indexJs, 'utf8');
    if (src.includes(FINALIZE_MARKER) && src.includes('set_shoot_phase BOOTSTRAPPING')) {
      return indexJs;
    }
  }
  throw new Error('FinalizeFn asset index.js not found in synth output');
}

describe('FinalizeFn Lambda bundle', () => {
  beforeAll(() => {
    if (fs.existsSync(SYNTH_OUT)) {
      fs.rmSync(SYNTH_OUT, { recursive: true, force: true });
    }
    const app = new cdk.App({ outdir: SYNTH_OUT });
    new PhotoPipelineStack(app, 'FinalizeBundleTestStack', {
      env: { account: '123456789012', region: 'us-east-1' },
    });
    app.synth();
  });

  it('embeds ec2 user-data bootstrap in the bundled handler (no runtime .sh file)', () => {
    const indexJs = findFinalizeAssetIndexJs();
    const src = fs.readFileSync(indexJs, 'utf8');
    expect(src).toContain('photopipeline-bootstrap');
    expect(src).toContain('python3 -m venv');
    expect(src).not.toMatch(/readFileSync\([^)]*ec2-userdata-bootstrap\.sh/);
  });

  it('loads the built handler module without ENOENT on bootstrap script', () => {
    const indexJs = findFinalizeAssetIndexJs();
    jest.isolateModules(() => {
      expect(() => {
        // eslint-disable-next-line @typescript-eslint/no-require-imports
        const mod = require(indexJs) as { handler?: unknown };
        expect(typeof mod.handler).toBe('function');
      }).not.toThrow();
    });
  });
});
