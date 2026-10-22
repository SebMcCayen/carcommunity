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

export interface AppleSubscriptionProviderConfig {
  environment: 'sandbox' | 'production';
  appAppleId?: number;
}

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

/**
 * Returns validated Apple verifier settings only while the provider is on.
 * Production requires the numeric App Store app id; malformed settings fail closed.
 */
export async function getAppleSubscriptionProviderConfig(): Promise<AppleSubscriptionProviderConfig | null> {
  try {
    const snap = await db.collection('config').doc('subscriptionProviders').get();
    const apple = snap.data()?.apple as Record<string, unknown> | undefined;
    if (apple?.enabled !== true) return null;
    if (apple.environment !== 'sandbox' && apple.environment !== 'production') return null;
    const appAppleId = apple.appAppleId;
    if (
      appAppleId !== undefined &&
      (typeof appAppleId !== 'number' || !Number.isSafeInteger(appAppleId) || appAppleId <= 0)
    ) {
      return null;
    }
    if (apple.environment === 'production' && appAppleId === undefined) return null;
    return { environment: apple.environment, ...(appAppleId === undefined ? {} : { appAppleId }) };
  } catch (error) {
    logger.warn('Apple subscription provider config read failed; failing closed', {
      error: String(error),
    });
    return null;
  }
}
