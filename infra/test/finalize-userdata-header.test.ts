import { readFileSync } from 'fs';
import { join } from 'path';

describe('finalize user-data header', () => {
  it('embeds per-shoot shutdown before bootstrap script', () => {
    const finalizeSrc = readFileSync(join(__dirname, '../lambda/finalize.ts'), 'utf8');
    expect(finalizeSrc).toMatch(/sudo shutdown -h \+\$\{maxRuntimeMinutes\}/);
    expect(finalizeSrc).toMatch(/export MAX_RUNTIME_MINUTES=\$\{maxRuntimeMinutes\}/);
    expect(finalizeSrc).toMatch(/from '\.\/ec2-userdata-bootstrap\.sh'/);
  });
});
