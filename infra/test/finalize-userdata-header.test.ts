import { readFileSync } from 'fs';
import { join } from 'path';

describe('finalize user-data header', () => {
  it('embeds per-shoot shutdown before bootstrap script', () => {
    const userDataSrc = readFileSync(join(__dirname, '../lambda/user-data-build.ts'), 'utf8');
    expect(userDataSrc).toMatch(/sudo shutdown -h \+\$\{maxRuntimeMinutes\}/);
    expect(userDataSrc).toMatch(/export MAX_RUNTIME_MINUTES=\$\{maxRuntimeMinutes\}/);
    const finalizeSrc = readFileSync(join(__dirname, '../lambda/finalize.ts'), 'utf8');
    expect(finalizeSrc).toMatch(/from '\.\/ec2-userdata-bootstrap\.sh'/);
    expect(finalizeSrc).toMatch(/normalizeLinuxLineEndings\(EC2_USERDATA_BOOTSTRAP\)/);
  });
});
