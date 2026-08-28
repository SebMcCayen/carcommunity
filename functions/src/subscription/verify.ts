/**
 * subscription.verify — authenticated callable, and
 * subscription.grantEntitlement — admin callable
 * (contracts/functions/functions.json), Phase 11.
 *
 * subscription.verify: the receipt-verification entry point. The legacy
 * implementation was itself a placeholder ("does not validate real
 * receipts"); the real Apple/Google adapters need store credentials that
 * land with the end-of-MVP console setup. Until
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
}

export const verify = onCall(
  CALLABLE_OPTS,
  async (request): Promise<VerifySubscriptionResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseVerifySubscriptionInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    // Hash immediately; the raw token must not survive this scope. The
    // hash is handed to the store adapter when one is wired — it is never
    // stored or logged.
    void hashPurchaseToken(parsed.input.purchaseToken);

    if (!(await isProviderEnabled(parsed.input.platform))) {
      // FAIL CLOSED: no store credentials → no entitlement, ever.
      throw new HttpsError(
        'failed-precondition',
        'Store receipt verification is not configured yet.',
      );
    }

    // Real adapter lands with the store credentials (end-of-MVP console
    // setup). Reaching this branch with a provider enabled but no adapter
    // wired is a deployment error — fail closed rather than trust input.
    // NOTE: no token-derived values in logs (matches the push-token rule).
    logger.error('Subscription provider enabled but no verification adapter is wired', {
      platform: parsed.input.platform,
      uid: actor.uid,
    });
    throw new HttpsError('unimplemented', 'Receipt verification adapter not available.');
  },
);

export interface GrantEntitlementResponse {
  targetUid: string;
  entitlement: string;
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
