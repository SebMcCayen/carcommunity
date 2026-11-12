import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { applyEntitlement } from './entitlement';
import {
  reservePurchaseVerification,
  releasePurchaseVerification,
} from './purchase-token-ownership';
import { hashPurchaseToken, type EntitlementRecordInput } from './subscription-core';
import {
  isNewerAppleState,
  readAppleEventVersion,
  type AppleSubscriptionState,
} from './apple-state-core';

/** Shared by the callable and webhook: both take the SAME per-account lease. */
export async function applyAppleSubscriptionState(
  uid: string,
  state: AppleSubscriptionState,
  now: Date,
  notificationsOnly = false,
): Promise<EntitlementRecordInput | null> {
  const { outcome } = state;
  const tokenHash = hashPurchaseToken(outcome.originalTransactionId);
  if (notificationsOnly) {
    const current = (await db.collection('subscriptions').doc(uid).get()).data();
    if (current?.platform !== 'apple' || current.purchaseTokenHash !== tokenHash) return null;
  }
  const reservation = await reservePurchaseVerification(
    tokenHash,
    { uid, productId: outcome.productId },
    now,
  );
  try {
    const [marker, subscription, user] = await Promise.all([
      db.collection('subscriptionAppleEvents').doc(tokenHash).get(),
      db.collection('subscriptions').doc(uid).get(),
      db.collection('users').doc(uid).get(),
    ]);
    // A late notification must not recreate an erased account or overwrite a
    // manual grant / another provider / a replacement subscription chain.
    if (!user.exists || user.data()?.deleted === true) return null;
    const current = subscription.data();
    if (
      notificationsOnly &&
      (current?.platform !== 'apple' || current.purchaseTokenHash !== tokenHash)
    )
      return null;
    const previous = readAppleEventVersion(marker.data());
    if (marker.exists && previous === null) throw new Error('Invalid Apple event watermark.');
    if (previous && !isNewerAppleState(state.version, previous)) {
      if (current?.platform !== 'apple' || current.purchaseTokenHash !== tokenHash) return null;
      // Reapply the canonical record to repair a failed Auth-claim write after
      // the atomic records+watermark commit. Never replay the old JWS outcome.
      const expiresAt = current.expiresAt instanceof Timestamp ? current.expiresAt.toDate() : null;
      const expired = expiresAt !== null && expiresAt <= now;
      const input: EntitlementRecordInput = {
        userId: uid,
        platform: 'apple',
        purchaseTokenHash: tokenHash,
        entitlement: expired ? 'none' : current.entitlement,
        status: expired ? 'expired' : current.status,
        tier: current.tier,
        startsAt: current.startsAt instanceof Timestamp ? current.startsAt.toDate() : null,
        expiresAt,
      };
      await applyEntitlement(input);
      return input;
    }
    const input: EntitlementRecordInput = {
      userId: uid,
      platform: 'apple',
      purchaseTokenHash: tokenHash,
      entitlement: outcome.entitlement,
      status: outcome.status,
      tier: outcome.tier,
      startsAt: outcome.startsAt,
      expiresAt: outcome.expiresAt,
    };
    await applyEntitlement(input, {
      appleEvent: { purchaseTokenHash: tokenHash, version: state.version },
    });
    return input;
  } finally {
    await releasePurchaseVerification(uid, tokenHash, reservation);
  }
}
