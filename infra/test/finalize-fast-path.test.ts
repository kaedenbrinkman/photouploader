import { readFileSync } from 'fs';
import { join } from 'path';

describe('finalize fast path', () => {
  const finalizeSrc = readFileSync(join(__dirname, '../lambda/finalize.ts'), 'utf8');

  it('does not HEAD every manifest object sequentially', () => {
    expect(finalizeSrc).toMatch(/manifestKeysMissingFromDynamo/);
    expect(finalizeSrc).toMatch(/spotCheckManifestOnS3/);
    expect(finalizeSrc).not.toMatch(
      /for\s*\(\s*const\s+f\s+of\s+manifest\.files[\s\S]*HeadObjectCommand/,
    );
  });

  it('enqueues async worker launch', () => {
    expect(finalizeSrc).toMatch(/enqueueAsyncLaunch/);
    expect(finalizeSrc).toMatch(/InvocationType:\s*'Event'/);
  });
});
