import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { supporterBadgeEligible } from './supporter-badge-core';
import { membershipMapRingProjection } from './map-ring-core';

/** Re-read in a transaction so a concurrent renewal/downgrade wins on retry.
 * Repairs only cosmetics and a private due field, never claims, receipt data or preference.
 */
export async function reconcileSupporterBadge(uid: string): Promise<void> {
  const profileRef = db.collection('users').doc(uid);
  const subscriptionRef = db.collection('subscriptions').doc(uid);
  await db.runTransaction(async (tx) => {
    const [profile, subscription] = await Promise.all([
      tx.get(profileRef),
      tx.get(subscriptionRef),
    ]);
    if (!profile.exists) {
      if (subscription.exists && subscription.get('mapRingRefreshAt') != null) {
        tx.update(subscriptionRef, { mapRingRefreshAt: FieldValue.delete() });
      }
      return;
    }
    const data = subscription.data();
    const expiry = data?.expiresAt;
    const record = data && {
      ...data,
      // Malformed dates must fail closed, not become perpetual manual grants.
      expiresAt: expiry == null ? null : (expiry?.toDate?.() ?? expiry),
    };
    const now = new Date();
    const eligible = supporterBadgeEligible(record, now);
    const ring = membershipMapRingProjection(record, now, uid);
    const storedRefresh = subscription.get('mapRingRefreshAt');
    const refreshMatches = ring.refreshAt === null
      ? storedRefresh == null
      : storedRefresh?.toMillis?.() === ring.refreshAt.getTime();
    if (subscription.exists && !refreshMatches) {
      // Private scheduling field only; never rewrite the verified receipt or claims.
      tx.update(subscriptionRef, { mapRingRefreshAt: ring.refreshAt ?? FieldValue.delete() });
    }
    if (
      profile.get('supporterBadgeEligible') !== eligible ||
      profile.get('membershipMapRingEligible') !== ring.membershipMapRingEligible
    ) {
      tx.update(profileRef, {
        supporterBadgeEligible: eligible,
        membershipMapRingEligible: ring.membershipMapRingEligible,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  });
}
