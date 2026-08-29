/**
 * Global purchase-token ownership.
 *
 * A Play purchase token may be verified repeatedly by its original Firebase
 * UID (restore/reinstall/retry), but never claimed by another UID. The document
 * id is only the SHA-256 token hash; raw tokens never reach Firestore.
 */

import { randomUUID } from 'node:crypto';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  PurchaseTokenOwnershipError,
  assertNoDifferentActiveToken,
  type PurchaseTokenOwnershipRecord,
  validateTokenOwnership,
} from './purchase-token-ownership-core';
import {
  SUBSCRIPTION_STATUSES,
  SUBSCRIPTION_TIERS,
  grantsLegacyActiveMember,
  type SubscriptionEntitlement,
  type SubscriptionStatus,
  type SubscriptionTier,
} from './subscription-core';

const COLLECTION = 'subscriptionPurchaseTokens';
const VERIFICATION_COLLECTION = 'subscriptionPurchaseVerifications';
export const PURCHASE_VERIFICATION_LEASE_MS = 2 * 60 * 1000;

export async function claimPurchaseTokenHash(
  purchaseTokenHash: string,
  expected: PurchaseTokenOwnershipRecord,
): Promise<void> {
  const ref = db.collection(COLLECTION).doc(purchaseTokenHash);
  await db.runTransaction(async (transaction) => {
    const snap = await transaction.get(ref);
    const decision = validateTokenOwnership(snap.exists ? snap.data() : null, expected);
    if (decision === 'create') {
      transaction.create(ref, {
        tokenHash: purchaseTokenHash,
        uid: expected.uid,
        productId: expected.productId,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
    } else {
      transaction.update(ref, { updatedAt: FieldValue.serverTimestamp() });
    }
  });
}

function storedDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

function storedEntitlement(value: unknown): SubscriptionEntitlement {
  return value === 'member_monthly' ? 'member_monthly' : 'none';
}

function storedStatus(value: unknown): SubscriptionStatus {
  return (SUBSCRIPTION_STATUSES as readonly unknown[]).includes(value)
    ? (value as SubscriptionStatus)
    : 'inactive';
}

function storedTier(value: unknown): SubscriptionTier | undefined {
  return (SUBSCRIPTION_TIERS as readonly unknown[]).includes(value)
    ? (value as SubscriptionTier)
    : undefined;
}

/**
 * Atomically claims global token ownership and the one-verification-at-a-time
 * slot for a UID. This closes the two-device race between the subscription
 * read and entitlement write. A crashed invocation leaves only a short lease.
 */
export async function reservePurchaseVerification(
  purchaseTokenHash: string,
  expected: PurchaseTokenOwnershipRecord,
  now: Date,
): Promise<string> {
  const tokenRef = db.collection(COLLECTION).doc(purchaseTokenHash);
  const verificationRef = db.collection(VERIFICATION_COLLECTION).doc(expected.uid);
  const subscriptionRef = db.collection('subscriptions').doc(expected.uid);
  const leaseExpiresAt = new Date(now.getTime() + PURCHASE_VERIFICATION_LEASE_MS);
  const reservationId = randomUUID();

  await db.runTransaction(async (transaction) => {
    const tokenSnap = await transaction.get(tokenRef);
    const verificationSnap = await transaction.get(verificationRef);
    const subscriptionSnap = await transaction.get(subscriptionRef);

    const tokenDecision = validateTokenOwnership(
      tokenSnap.exists ? tokenSnap.data() : null,
      expected,
    );

    if (verificationSnap.exists) {
      const selection = verificationSnap.data();
      const selectedHash = selection?.purchaseTokenHash;
      const selectedUntil = storedDate(selection?.leaseExpiresAt);
      if (typeof selectedHash !== 'string' || selectedUntil === null) {
        throw new PurchaseTokenOwnershipError('malformed_record');
      }
      if (selectedUntil.getTime() > now.getTime()) {
        throw new PurchaseTokenOwnershipError(
          selectedHash === purchaseTokenHash
            ? 'verification_in_progress'
            : 'different_active_token',
        );
      }
    }

    const current = subscriptionSnap.data();
    if (current) {
      const entitlement = storedEntitlement(current.entitlement);
      const status = storedStatus(current.status);
      const tier = storedTier(current.tier);
      const currentTokenHash =
        typeof current.purchaseTokenHash === 'string' ? current.purchaseTokenHash : null;
      assertNoDifferentActiveToken(
        {
          grantsAccess: grantsLegacyActiveMember({ entitlement, status, tier }),
          purchaseTokenHash: currentTokenHash,
          expiresAt: storedDate(current.expiresAt),
        },
        purchaseTokenHash,
        now,
      );
    }

    if (tokenDecision === 'create') {
      transaction.create(tokenRef, {
        tokenHash: purchaseTokenHash,
        uid: expected.uid,
        productId: expected.productId,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
    } else {
      transaction.update(tokenRef, { updatedAt: FieldValue.serverTimestamp() });
    }
    transaction.set(verificationRef, {
      uid: expected.uid,
      productId: expected.productId,
      purchaseTokenHash,
      reservationId,
      leaseExpiresAt: Timestamp.fromDate(leaseExpiresAt),
      updatedAt: FieldValue.serverTimestamp(),
    });
  });

  return reservationId;
}

/** Releases only the reservation owned by this invocation's token. */
export async function releasePurchaseVerification(
  uid: string,
  purchaseTokenHash: string,
  reservationId: string,
): Promise<void> {
  const ref = db.collection(VERIFICATION_COLLECTION).doc(uid);
  await db.runTransaction(async (transaction) => {
    const snap = await transaction.get(ref);
    if (
      snap.exists &&
      snap.data()?.purchaseTokenHash === purchaseTokenHash &&
      snap.data()?.reservationId === reservationId
    ) {
      transaction.delete(ref);
    }
  });
}
