import { canReprocessShoot, countPublishedFiles } from '../lambda/finalize-eligibility';

describe('finalize eligibility', () => {
  it('counts GP_CREATED files', () => {
    expect(
      countPublishedFiles([{ state: 'GP_CREATED' }, { state: 'RENDERED' }]),
    ).toBe(1);
  });

  it('allows PARTIAL and FAILED without force', () => {
    expect(canReprocessShoot({ state: 'PARTIAL' }, [], {})).toBe(true);
    expect(canReprocessShoot({ state: 'FAILED' }, [], {})).toBe(true);
  });

  it('allows DONE when zero published', () => {
    expect(
      canReprocessShoot({ state: 'DONE', publishedCount: 0, totalFiles: 13 }, [], {}),
    ).toBe(true);
  });

  it('blocks DONE when fully published', () => {
    expect(
      canReprocessShoot(
        { state: 'DONE', publishedCount: 13, totalFiles: 13 },
        [{ state: 'GP_CREATED' }],
        {},
      ),
    ).toBe(false);
  });

  it('force allows complete DONE', () => {
    expect(
      canReprocessShoot(
        { state: 'DONE', publishedCount: 13, totalFiles: 13 },
        [{ state: 'GP_CREATED' }],
        { force: true },
      ),
    ).toBe(true);
  });
});
