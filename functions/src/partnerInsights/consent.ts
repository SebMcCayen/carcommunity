import { Timestamp } from 'firebase-admin/firestore';

/** Legacy booleans were provisioned ON; they are not evidence of an active choice. */
export const PARTNER_STATS_CONSENT_VERSION = 1;

export function readPartnerStatsConsent(
  data: Record<string, unknown> | undefined,
): { version: 1; grantedAt: Timestamp } | null {
  const at = data?.anonymousPartnerStatsConsentAt;
  if (
    data?.anonymousPartnerStatsOptIn !== true ||
    data.anonymousPartnerStatsConsentVersion !== PARTNER_STATS_CONSENT_VERSION ||
    !(at instanceof Timestamp) ||
    at.toMillis() <= 0
  )
    return null;
  return { version: PARTNER_STATS_CONSENT_VERSION, grantedAt: at };
}
