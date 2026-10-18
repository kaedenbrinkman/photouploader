import { readFileSync } from 'fs';
import { join } from 'path';

describe('EC2 user-data bootstrap script', () => {
  const sh = readFileSync(
    join(__dirname, '../lambda/ec2-userdata-bootstrap.sh'),
    'utf8',
  );

  it('uses venv and vendored wheels instead of system pip', () => {
    expect(sh).toMatch(/python3 -m venv/);
    expect(sh).toMatch(/--no-index --find-links="\$VENDOR"/);
    expect(sh).not.toMatch(/python3 -m pip install.*requirements\.txt/);
  });

  it('traps errors to report_fail', () => {
    expect(sh).toMatch(/trap 'on_err \$LINENO' ERR/);
    expect(sh).toMatch(/report_fail/);
    expect(sh).not.toMatch(/shutdown -h \+30/);
  });

  it('updates shoot phase BOOTSTRAPPING then RENDERING', () => {
    expect(sh).toMatch(/set_shoot_phase BOOTSTRAPPING/);
    expect(sh).toMatch(/set_shoot_phase RENDERING/);
  });

  it('includes redacted bootstrap log tail in lastError on failure', () => {
    expect(sh).toMatch(/splitlines\(\)\[-50:\]/);
    expect(sh).toMatch(/\[REDACTED\]/);
  });
});
