import { describe, expect, it } from 'vitest';
import { hasPartnerStatsConsent } from './partner-consent';

describe('affirmative partner analysis consent', () => {
  const stamp = { toMillis: () => 1_700_000_000_000 };
  it('excludes old default-on, missing, false and malformed choices', () => {
    for (const data of [
      undefined,
      {},
      { anonymousPartnerStatsOptIn: true },
      { anonymousPartnerStatsOptIn: true, partnerStatsConsentVersion: 1 },
      {
        anonymousPartnerStatsOptIn: true,
        partnerStatsConsentVersion: 0,
        partnerStatsConsentedAt: stamp,
      },
      {
        anonymousPartnerStatsOptIn: false,
        partnerStatsConsentVersion: 1,
        partnerStatsConsentedAt: stamp,
      },
    ]) {
      expect(hasPartnerStatsConsent(data)).toBe(false);
    }
  });
  it('accepts only the current explicit choice with its recorded timestamp', () => {
    expect(
      hasPartnerStatsConsent({
        anonymousPartnerStatsOptIn: true,
        partnerStatsConsentVersion: 1,
        partnerStatsConsentedAt: stamp,
      }),
    ).toBe(true);
  });
});
