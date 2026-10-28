/** A legacy default-on boolean is never evidence of an affirmative choice. */
export const PARTNER_STATS_CONSENT_VERSION = 1;
export function hasPartnerStatsConsent(data: Record<string, unknown> | undefined): boolean {
  const stamp = data?.partnerStatsConsentedAt as { toMillis?: () => number } | undefined;
  return (
    data?.anonymousPartnerStatsOptIn === true &&
    data.partnerStatsConsentVersion === PARTNER_STATS_CONSENT_VERSION &&
    typeof stamp?.toMillis === 'function' &&
    Number.isFinite(stamp.toMillis()) &&
    stamp.toMillis() > 0
  );
}
