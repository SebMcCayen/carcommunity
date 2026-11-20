import { Timestamp } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { readPartnerStatsConsent } from './consent';

const grantedAt = Timestamp.fromMillis(1_000);
const consent = {
  anonymousPartnerStatsOptIn: true,
  anonymousPartnerStatsConsentVersion: 1,
  anonymousPartnerStatsConsentAt: grantedAt,
};

describe('readPartnerStatsConsent', () => {
  it('accepts affirmative version 1 consent and retains its actual server timestamp', () => {
    expect(readPartnerStatsConsent(consent)).toStrictEqual({ version: 1, grantedAt });
  });

  it.each([
    undefined,
    {},
    { anonymousPartnerStatsOptIn: true },
    { anonymousPartnerStatsOptIn: false },
  ])('never treats missing or legacy state %j as affirmative consent', (data) => {
    expect(readPartnerStatsConsent(data)).toBeNull();
  });

  it.each([false, undefined, null, 1, 'true', { valueOf: () => true }])(
    'requires an actual affirmative boolean, rejecting %j',
    (value) => {
      expect(readPartnerStatsConsent({ ...consent, anonymousPartnerStatsOptIn: value })).toBeNull();
    },
  );

  it.each([undefined, null, 0, 2, -1, 1.5, '1', true, Number.NaN, Number.POSITIVE_INFINITY])(
    'requires numeric version 1, rejecting %j',
    (version) => {
      expect(
        readPartnerStatsConsent({ ...consent, anonymousPartnerStatsConsentVersion: version }),
      ).toBeNull();
    },
  );

  it.each([
    undefined,
    null,
    1_000,
    '2026-11-20T00:00:00.000Z',
    new Date(1_000),
    { seconds: 1, nanoseconds: 0 },
    { toMillis: (): number => 1_000 },
    Timestamp.fromMillis(0),
    Timestamp.fromMillis(-1),
  ])('requires a positive actual Admin Timestamp, rejecting %j', (at) => {
    expect(readPartnerStatsConsent({ ...consent, anonymousPartnerStatsConsentAt: at })).toBeNull();
  });

  it('does not depend on optional analytics or unrelated leaderboard fields', () => {
    expect(
      readPartnerStatsConsent({ ...consent, leaderboardOptOut: true, otherField: false }),
    ).toStrictEqual({ version: 1, grantedAt });
  });
});
