import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { supporterBadgeEligible } from './supporter-badge-core';

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
    const eligible = supporterBadgeEligible(record, new Date());
    if (profile.get('supporterBadgeEligible') !== eligible) {
      tx.update(profileRef, {
        supporterBadgeEligible: eligible,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  });
}
