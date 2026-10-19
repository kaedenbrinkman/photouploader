import { readFileSync } from 'fs';
import { join } from 'path';

describe('finalize user-data header', () => {
  it('embeds per-shoot shutdown before bootstrap script', () => {
    const userDataSrc = readFileSync(join(__dirname, '../lambda/user-data-build.ts'), 'utf8');
    expect(userDataSrc).toMatch(/sudo shutdown -h \+\$\{maxRuntimeMinutes\}/);
    expect(userDataSrc).toMatch(/export MAX_RUNTIME_MINUTES=\$\{maxRuntimeMinutes\}/);
    const launchSrc = readFileSync(join(__dirname, '../lambda/finalize-launch-worker.ts'), 'utf8');
    expect(launchSrc).toMatch(/from '\.\/ec2-userdata-bootstrap\.sh'/);
    expect(launchSrc).toMatch(/normalizeLinuxLineEndings\(EC2_USERDATA_BOOTSTRAP\)/);
  });
});
