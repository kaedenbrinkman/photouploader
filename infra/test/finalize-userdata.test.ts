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

  it('arms bootstrap backstop and traps errors to report_fail', () => {
    expect(sh).toMatch(/shutdown -h \+30/);
    expect(sh).toMatch(/trap 'on_err \$LINENO' ERR/);
    expect(sh).toMatch(/report_fail/);
  });
});
