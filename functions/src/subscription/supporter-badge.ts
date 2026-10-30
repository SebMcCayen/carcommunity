import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { supporterBadgeEligible } from './supporter-badge-core';
import { membershipMapRingProjection } from './map-ring-core';

/** Re-read in a transaction so a concurrent renewal/downgrade wins on retry.
 * Repairs only cosmetic profile fields, never claims, subscription or preference.
 */
export async function reconcileSupporterBadge(uid: string): Promise<void> {
  const profileRef = db.collection('users').doc(uid);
  const subscriptionRef = db.collection('subscriptions').doc(uid);
  await db.runTransaction(async (tx) => {
    const [profile, subscription] = await Promise.all([
      tx.get(profileRef),
      tx.get(subscriptionRef),
    ]);
    if (!profile.exists) return;
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
    const storedDeadline = profile.get('membershipMapRingUntil');
    const deadlineMatches = ring.membershipMapRingUntil === null
      ? storedDeadline === null
      : storedDeadline?.toMillis?.() === ring.membershipMapRingUntil.getTime();
    if (
      profile.get('supporterBadgeEligible') !== eligible ||
      profile.get('membershipMapRingEligible') !== ring.membershipMapRingEligible ||
      !deadlineMatches
    ) {
      tx.update(profileRef, {
        supporterBadgeEligible: eligible,
        ...ring,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  });
}
