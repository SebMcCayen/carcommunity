/**
 * subscription.verify — authenticated callable, and
 * subscription.grantEntitlement — admin callable
 * (contracts/functions/functions.json), Phase 11.
 *
 * subscription.verify: the receipt-verification entry point. The legacy
 * implementation was itself a placeholder ("does not validate real
 * receipts"). Google Play is verified with Android Publisher
 * purchases.subscriptionsv2.get under a dedicated ADC runtime identity. Until
 * `config/subscriptionProviders.{apple|google}.enabled` is true the
 * callable FAILS CLOSED (failed-precondition) — no code path grants
 * entitlement from an unverified receipt. The raw purchase token is
 * hashed immediately and never stored, logged, or returned.
 *
 * subscription.grantEntitlement: the manual platform (reserved by the legacy
 * enum) — audited admin grant/revoke of member_monthly. This is the
 * operational path until store verification is wired, and the test path
 * for the whole entitlement chain (record + users flag + claim).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { applyEntitlement } from './entitlement';
import {
  SUBSCRIPTION_STATUSES,
  SUBSCRIPTION_TIERS,
  hashPurchaseToken,
  isPaidSubscriptionTier,
  isSubscriptionActiveStatus,
  parseGrantEntitlementInput,
  parseVerifySubscriptionInput,
  resolveSubscriptionTier,
  subscriptionTierForLegacyEntitlement,
  type SubscriptionEntitlement,
  type SubscriptionStatus,
  type SubscriptionTier,
} from './subscription-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  GOOGLE_PLAY_RUNTIME_SERVICE_ACCOUNT,
  GooglePlayVerificationError,
  obfuscatedAccountIdForUid,
} from './google-play-core';
import {
  AdcGooglePlaySubscriptionClient,
  GooglePlayApiError,
  verifyGooglePlaySubscription,
} from './google-play';
import {
  releasePurchaseVerification,
  reservePurchaseVerification,
} from './purchase-token-ownership';
import {
  PurchaseTokenOwnershipError,
} from './purchase-token-ownership-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

async function isProviderEnabled(platform: 'apple' | 'google'): Promise<boolean> {
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

export interface VerifySubscriptionResponse {
  entitlement: string;
  status: string;
  tier: SubscriptionTier;
}

export const verify = onCall(
  {
    ...CALLABLE_OPTS,
    serviceAccount: GOOGLE_PLAY_RUNTIME_SERVICE_ACCOUNT,
  },
  async (request): Promise<VerifySubscriptionResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseVerifySubscriptionInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    // Hash immediately. The raw token is needed only for the provider calls in
    // this invocation; it is never logged, persisted, or returned.
    const purchaseTokenHash = hashPurchaseToken(parsed.input.purchaseToken);

    if (!(await isProviderEnabled(parsed.input.platform))) {
      // FAIL CLOSED: no store credentials → no entitlement, ever.
      throw new HttpsError(
        'failed-precondition',
        'Store receipt verification is not configured yet.',
      );
    }

    if (parsed.input.platform !== 'google') {
      // TEMPORARY PLATFORM EXCEPTION — ADR-002, "Milestones gated on the
      // Apple Developer Program membership": StoreKit 2 against App Store
      // Connect and Apple transaction verification are deferred until paid
      // membership exists. Apple therefore remains disabled and fail-closed
      // in this Google Play Internal Testing slice.
      logger.error('Subscription provider enabled but adapter is unavailable', {
        platform: parsed.input.platform,
        uid: actor.uid,
      });
      throw new HttpsError('unimplemented', 'Receipt verification adapter not available.');
    }

    const client = new AdcGooglePlaySubscriptionClient();
    let verificationReservationId: string | null = null;
    try {
      const verificationNow = new Date();
      const outcome = await verifyGooglePlaySubscription(client, {
        purchaseToken: parsed.input.purchaseToken,
        expectedObfuscatedAccountId: obfuscatedAccountIdForUid(actor.uid),
        now: verificationNow,
      });

      // Claim only after Play has authenticated the response and UID binding.
      // Token ownership and the UID's verification slot are one transaction,
      // so two devices cannot both pass a stale subscription read and apply
      // different first-purchase tokens concurrently.
      verificationReservationId = await reservePurchaseVerification(
        purchaseTokenHash,
        { uid: actor.uid, productId: outcome.productId },
        verificationNow,
      );

      await applyEntitlement({
        userId: actor.uid,
        platform: 'google',
        status: outcome.status,
        entitlement: outcome.entitlement,
        tier: outcome.tier,
        purchaseTokenHash,
        startsAt: outcome.startsAt,
        expiresAt: outcome.expiresAt,
      });

      // Google recommends server-side acknowledgement immediately after the
      // verified entitlement is granted. Renewals are already acknowledged;
      // pending/inactive purchases are never acknowledged. A transient failure
      // makes the callable fail so route-open reconciliation retries it.
      if (outcome.entitlement === 'member_monthly' && outcome.acknowledgementRequired) {
        await client.acknowledgeSubscription(outcome.productId, parsed.input.purchaseToken);
      }

      return {
        entitlement: outcome.entitlement,
        status: outcome.status,
        tier: outcome.tier,
      };
    } catch (error) {
      // Never interpolate provider errors: HTTP clients commonly include the
      // raw URL, and this endpoint embeds the purchase token in the path.
      if (error instanceof GooglePlayVerificationError) {
        logger.warn('Google Play subscription verification rejected', {
          reason: error.code,
          uid: actor.uid,
        });
        throw new HttpsError('failed-precondition', 'Google Play purchase is not valid.');
      }
      if (error instanceof PurchaseTokenOwnershipError) {
        logger.warn('Google Play purchase-token ownership rejected', {
          reason: error.reason,
          uid: actor.uid,
        });
        switch (error.reason) {
          case 'different_user':
            throw new HttpsError(
              'already-exists',
              'Google Play purchase belongs to another account.',
            );
          case 'different_product':
            throw new HttpsError(
              'failed-precondition',
              'Google Play purchase does not match its original subscription product.',
            );
          case 'different_active_token':
            throw new HttpsError(
              'failed-precondition',
              'A different subscription is already active for this account.',
            );
          case 'verification_in_progress':
            throw new HttpsError(
              'aborted',
              'Subscription verification is already in progress. Please retry shortly.',
            );
          case 'malformed_record':
            throw new HttpsError('internal', 'Subscription ownership record is invalid.');
        }
      }
      if (error instanceof GooglePlayApiError) {
        if (error.operation === 'get' && error.reason === 'invalid_purchase') {
          logger.warn('Google Play rejected purchase token', {
            operation: error.operation,
            uid: actor.uid,
          });
          throw new HttpsError('failed-precondition', 'Google Play purchase is not valid.');
        }
        logger.error('Google Play subscription request failed', {
          operation: error.operation,
          uid: actor.uid,
        });
        throw new HttpsError('unavailable', 'Google Play verification is temporarily unavailable.');
      }
      logger.error('Google Play subscription verification failed unexpectedly', {
        uid: actor.uid,
      });
      throw new HttpsError('internal', 'Google Play verification failed.');
    } finally {
      if (verificationReservationId !== null) {
        try {
          await releasePurchaseVerification(
            actor.uid,
            purchaseTokenHash,
            verificationReservationId,
          );
        } catch {
          // A stale lease expires automatically; never turn a successful
          // entitlement result into a client-visible failure during cleanup.
          logger.warn('Google Play verification lease release failed', { uid: actor.uid });
        }
      }
    }
  },
);

export interface GrantEntitlementResponse {
  targetUid: string;
  entitlement: SubscriptionEntitlement;
  tier: SubscriptionTier;
}

function storedDate(value: unknown): Date | null {
  if (value == null) return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    const date = (value as { toDate: () => Date }).toDate();
    return Number.isNaN(date.getTime()) ? null : date;
  }
  return null;
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

export const grantEntitlement = onCall(
  CALLABLE_OPTS,
  async (request): Promise<GrantEntitlementResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseGrantEntitlementInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { targetUid, entitlement, tier, reason, expiresAt } = parsed.input;

    const [targetSnap, currentSubscriptionSnap] = await Promise.all([
      db.collection('users').doc(targetUid).get(),
      db.collection('subscriptions').doc(targetUid).get(),
    ]);
    if (!targetSnap.exists) {
      throw new HttpsError('not-found', 'Target user not found.');
    }

    const granting = entitlement === 'member_monthly';
    const current = currentSubscriptionSnap.data();
    const currentEntitlement = storedEntitlement(current?.entitlement);
    const currentStatus = storedStatus(current?.status);
    const explicitStoredTier = storedTier(current?.tier);
    const currentStoredTier =
      explicitStoredTier ?? subscriptionTierForLegacyEntitlement(currentEntitlement);
    const currentEffectiveTier = resolveSubscriptionTier({
      entitlement: currentEntitlement,
      tier: explicitStoredTier,
    });
    const currentIsPaid =
      currentEntitlement === 'member_monthly' &&
      isPaidSubscriptionTier(currentEffectiveTier) &&
      isSubscriptionActiveStatus(currentStatus);
    const nextTier = granting
      ? (tier ?? (currentIsPaid ? currentStoredTier : 'plus'))
      : currentStoredTier;
    const previousStartsAt = storedDate(current?.startsAt);
    const startsAt = granting ? (currentIsPaid ? previousStartsAt : new Date()) : previousStartsAt;

    await applyEntitlement(
      {
        userId: targetUid,
        platform: 'manual',
        status: granting ? 'active' : 'revoked',
        entitlement,
        tier: nextTier,
        purchaseTokenHash: null,
        startsAt,
        expiresAt: expiresAt ? new Date(expiresAt) : null,
      },
      {
        auditEvent: buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'subscription.grantEntitlement',
            targetType: 'user',
            targetId: targetUid,
            reason,
            details: { entitlement, tier: nextTier, platform: 'manual' },
          },
          () => FieldValue.serverTimestamp(),
        ),
      },
    );

    return { targetUid, entitlement, tier: nextTier };
  },
);
