/**
 * Subscription provider gate — the single fail-closed reader of
 * `config/subscriptionProviders`.
 *
 * Extracted from subscription/verify.ts so every entitlement-mutating surface
 * (the verify callable, the RTDN Pub/Sub handler, and the reconciliation sweep)
 * reads the SAME switch the same way: if the document is missing, the platform
 * key is absent, or the read throws, the provider is treated as DISABLED. No
 * code path may grant, refresh, or revoke store entitlement while the provider
 * is off — the feature deploys inert until an operator flips this in the
 * console with real store credentials (end-of-MVP setup).
 */

import { logger } from 'firebase-functions';
import { db } from '../firebase';

/** True only when `config/subscriptionProviders.{platform}.enabled === true`. */
export async function isSubscriptionProviderEnabled(
  platform: 'apple' | 'google',
): Promise<boolean> {
  try {
    const snap = await db.collection('config').doc('subscriptionProviders').get();
    return (snap.data()?.[platform] as { enabled?: boolean } | undefined)?.enabled === true;
  } catch (error) {
    logger.warn('Subscription provider config read failed; failing closed', {
      error: String(error),
    });
    return false;
  }
}
