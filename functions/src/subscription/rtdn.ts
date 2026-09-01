/**
 * subscription-rtdn — the Google Play Real-time Developer Notifications
 * handler (a Pub/Sub-triggered function, functions v2 onMessagePublished).
 *
 * Play pushes a `DeveloperNotification` to a Pub/Sub topic whenever a
 * subscription changes (purchase, renewal, cancel, grace, hold, pause,
 * restart, revoke, expiry) or a purchase is VOIDED (refund/chargeback). This
 * handler turns each notification into the correct entitlement transition —
 * WITHOUT trusting the notification body: per Google's guidance it re-fetches
 * the AUTHORITATIVE state from `purchases.subscriptionsv2.get` and applies
 * that, reusing the exact validation the verify callable uses.
 *
 * DESIGN INVARIANTS (all shared with the verify callable):
 *   - FAIL CLOSED / PROVIDER-GATED. If `config/subscriptionProviders.google`
 *     is not enabled the handler is a safe no-op: it never calls the Play API
 *     and never mutates entitlement. The whole feature deploys INERT until an
 *     operator enables the provider (end-of-MVP console setup).
 *   - applyEntitlement IS THE ONLY WRITER. Every transition (keep-active on
 *     renew/recover, downgrade/revoke on cancel-expired / on-hold / paused /
 *     revoked / refunded) goes through applyEntitlement, so all three
 *     representations (subscriptions record + users.activeMember + the
 *     activeMember custom claim) move together with fail-safe privilege
 *     ordering.
 *   - IDEMPOTENT. Play redelivers, reorders and duplicates messages. A
 *     per-token processed marker keyed on the monotonic `eventTimeMillis`
 *     skips any delivery not newer than the last one applied; the apply itself
 *     is idempotent (it writes fixed values derived from authoritative state),
 *     so even a concurrent duplicate converges to the same result.
 *   - FAIL SAFE ON POISON. An undecodable / unknown / foreign-package message
 *     is logged and ACKED (returns), never thrown, so it cannot wedge Pub/Sub
 *     in an infinite redelivery loop. Only a TRANSIENT downstream failure
 *     (Play API unavailable, a Firestore/Auth blip) throws, which is exactly
 *     when a Pub/Sub retry is wanted.
 *   - NO RAW TOKEN AT REST. The raw purchase token is used only for the
 *     in-invocation Play call and hashed the instant ownership is resolved; it
 *     is never stored, logged, or returned (the repo-wide rule).
 *
 * runRtdnNotification(decoded, deps) is exported for deterministic unit tests
 * (same pattern as runSubscriptionExpirySweep): the Play client, ownership
 * registry, provider gate and processed-marker store are all injected.
 */

import { onMessagePublished } from 'firebase-functions/v2/pubsub';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { CPU_TRIGGER, MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';
import { applyEntitlement } from './entitlement';
import { isSubscriptionProviderEnabled } from './provider-config';
import {
  GOOGLE_PLAY_PRODUCT_IDS,
  GOOGLE_PLAY_RUNTIME_SERVICE_ACCOUNT,
  GooglePlayVerificationError,
  type GooglePlayEntitlementOutcome,
  type GooglePlayProductId,
  obfuscatedAccountIdForUid,
  tierForGooglePlayProduct,
} from './google-play-core';
import {
  AdcGooglePlaySubscriptionClient,
  GooglePlayApiError,
  verifyGooglePlaySubscription,
} from './google-play';
import { hashPurchaseToken, type EntitlementRecordInput } from './subscription-core';
import {
  type DecodedRtdn,
  decodeRtdnMessageData,
  describeSubscriptionNotificationType,
  isExpectedRtdnPackage,
  RTDN_TOPIC,
} from './rtdn-core';

/** Backend-only marker collection giving each purchase token an idempotency high-watermark. */
const RTDN_EVENTS_COLLECTION = 'subscriptionRtdnEvents';

/** Resolved owner of a hashed purchase token (from the verify-path registry). */
export interface RtdnTokenOwner {
  uid: string;
  productId: GooglePlayProductId;
}

/** Everything the handler touches, injected so unit tests need no emulator. */
export interface RtdnDeps {
  providerEnabled: (platform: 'google') => Promise<boolean>;
  /** Resolve the hashed token to its owning UID via subscriptionPurchaseTokens. */
  resolveOwner: (purchaseTokenHash: string) => Promise<RtdnTokenOwner | null>;
  /** Authoritative Play re-verification (subscriptionsv2.get + validation). */
  verify: (
    purchaseToken: string,
    expectedObfuscatedAccountId: string,
    now: Date,
  ) => Promise<GooglePlayEntitlementOutcome>;
  applyEntitlement: (input: EntitlementRecordInput) => Promise<void>;
  /** Historical fields of the current subscriptions/{uid} record, for revoke. */
  readStoredSubscription: (uid: string) => Promise<StoredSubscriptionFields | null>;
  /** Idempotency high-watermark for a token (the last eventTimeMillis applied). */
  lastProcessedEventTime: (purchaseTokenHash: string) => Promise<number | null>;
  /** Advance the high-watermark (monotonic). */
  markProcessed: (
    purchaseTokenHash: string,
    eventTimeMillis: number,
    reason: string,
  ) => Promise<void>;
  now: () => Date;
}

export interface StoredSubscriptionFields {
  tier?: string;
  startsAt: Date | null;
  expiresAt: Date | null;
  purchaseTokenHash: string | null;
}

export type RtdnOutcome =
  | 'provider_disabled'
  | 'foreign_package'
  | 'test_notification'
  | 'unknown_token'
  | 'duplicate'
  | 'applied'
  | 'revoked'
  | 'verification_rejected';

/**
 * A transient failure that SHOULD be retried by Pub/Sub. Thrown only for
 * Play-API-unavailable and unexpected downstream errors; never for a decode
 * failure or a business no-op (those ack).
 */
export class TransientRtdnError extends Error {
  constructor(reason: string) {
    super(`Transient RTDN processing failure: ${reason}`);
    this.name = 'TransientRtdnError';
  }
}

/**
 * Builds the revoke input for a voided/refunded purchase, preserving the
 * record's historical tier/dates when a record still exists (applyEntitlement
 * rewrites the document merge-less, so anything not carried through is lost),
 * falling back to the tier implied by the token's product id.
 */
function buildRevokeInput(
  uid: string,
  productId: GooglePlayProductId,
  purchaseTokenHash: string,
  stored: StoredSubscriptionFields | null,
): EntitlementRecordInput {
  const tier =
    stored?.tier === 'plus' || stored?.tier === 'supporter'
      ? stored.tier
      : tierForGooglePlayProduct(productId);
  return {
    userId: uid,
    platform: 'google',
    status: 'revoked',
    entitlement: 'none',
    tier,
    purchaseTokenHash: stored?.purchaseTokenHash ?? purchaseTokenHash,
    startsAt: stored?.startsAt ?? null,
    expiresAt: stored?.expiresAt ?? null,
  };
}

/**
 * Core orchestration for one decoded RTDN. Returns an outcome for every
 * handled case; THROWS TransientRtdnError only when a retry is warranted.
 */
export async function runRtdnNotification(
  decoded: DecodedRtdn,
  deps: RtdnDeps,
): Promise<RtdnOutcome> {
  if (!isExpectedRtdnPackage(decoded.packageName)) {
    logger.warn('RTDN for an unexpected package; ignoring', { packageName: decoded.packageName });
    return 'foreign_package';
  }

  // PROVIDER GATE — no Play call, no entitlement mutation while disabled.
  if (!(await deps.providerEnabled('google'))) {
    logger.info('RTDN received while Google provider disabled; no-op', { kind: decoded.kind });
    return 'provider_disabled';
  }

  if (decoded.kind === 'test') {
    logger.info('RTDN test notification received', { eventTimeMillis: decoded.eventTimeMillis });
    return 'test_notification';
  }

  const purchaseTokenHash = hashPurchaseToken(decoded.notification.purchaseToken);

  const owner = await deps.resolveOwner(purchaseTokenHash);
  if (owner === null) {
    // Unknown token — a purchase we never verified, or one already purged.
    // Nothing to do; ack so Pub/Sub does not redeliver forever.
    logger.warn('RTDN for an unknown purchase token; ignoring', { kind: decoded.kind });
    return 'unknown_token';
  }

  // IDEMPOTENCY — skip a delivery not strictly newer than the last applied.
  const lastProcessed = await deps.lastProcessedEventTime(purchaseTokenHash);
  if (lastProcessed !== null && decoded.eventTimeMillis <= lastProcessed) {
    logger.info('RTDN duplicate/stale delivery skipped', {
      uid: owner.uid,
      kind: decoded.kind,
    });
    return 'duplicate';
  }

  const now = deps.now();

  if (decoded.kind === 'voided') {
    // A refund/chargeback is authoritative on its own — the money was
    // returned — so we revoke without trusting (or needing) the current Play
    // subscription state.
    const stored = await deps.readStoredSubscription(owner.uid);
    await deps.applyEntitlement(
      buildRevokeInput(owner.uid, owner.productId, purchaseTokenHash, stored),
    );
    await deps.markProcessed(purchaseTokenHash, decoded.eventTimeMillis, 'voided');
    logger.info('RTDN voided purchase → entitlement revoked', { uid: owner.uid });
    return 'revoked';
  }

  // kind === 'subscription' → re-fetch AUTHORITATIVE state and apply it.
  const type = describeSubscriptionNotificationType(decoded.notification.notificationType);
  let outcome: GooglePlayEntitlementOutcome;
  try {
    outcome = await deps.verify(
      decoded.notification.purchaseToken,
      obfuscatedAccountIdForUid(owner.uid),
      now,
    );
  } catch (error) {
    if (error instanceof GooglePlayVerificationError) {
      // Malformed/account-mismatch/unsupported/unknown-state: we cannot
      // SAFELY act, so ack + log rather than loop. (An account mismatch here
      // would mean the registry and Play disagree on ownership — investigate,
      // never blindly mutate.)
      logger.warn('RTDN authoritative verification rejected; ignoring', {
        uid: owner.uid,
        reason: error.code,
        type,
      });
      return 'verification_rejected';
    }
    if (error instanceof GooglePlayApiError && error.reason === 'invalid_purchase') {
      // Play definitively no longer recognises the token (400/404/410) — the
      // purchase is gone. Authoritative "no entitlement": revoke.
      const stored = await deps.readStoredSubscription(owner.uid);
      await deps.applyEntitlement(
        buildRevokeInput(owner.uid, owner.productId, purchaseTokenHash, stored),
      );
      await deps.markProcessed(purchaseTokenHash, decoded.eventTimeMillis, `invalid_purchase:${type}`);
      logger.info('RTDN token no longer valid at Play → entitlement revoked', { uid: owner.uid });
      return 'revoked';
    }
    // Play unavailable, or an unexpected downstream error → retry.
    logger.error('RTDN authoritative verification unavailable; will retry', {
      uid: owner.uid,
      type,
    });
    throw new TransientRtdnError('play_verification_unavailable');
  }

  // Apply the authoritative outcome verbatim. active/grace/cancelled →
  // member_monthly (access kept); expired/inactive (paused/on-hold/pending/
  // pending-cancel) → none (access removed). grantsLegacyActiveMember inside
  // applyEntitlement derives the flag/claim from these fields.
  await deps.applyEntitlement({
    userId: owner.uid,
    platform: 'google',
    status: outcome.status,
    entitlement: outcome.entitlement,
    tier: outcome.tier,
    purchaseTokenHash,
    startsAt: outcome.startsAt,
    expiresAt: outcome.expiresAt,
  });
  await deps.markProcessed(purchaseTokenHash, decoded.eventTimeMillis, `subscription:${type}`);
  logger.info('RTDN subscription notification applied', {
    uid: owner.uid,
    type,
    status: outcome.status,
    entitlement: outcome.entitlement,
  });
  return outcome.entitlement === 'member_monthly' ? 'applied' : 'revoked';
}

// ---------------------------------------------------------------------------
// Production dependency wiring
// ---------------------------------------------------------------------------

async function resolveOwnerFromRegistry(purchaseTokenHash: string): Promise<RtdnTokenOwner | null> {
  const snap = await db.collection('subscriptionPurchaseTokens').doc(purchaseTokenHash).get();
  if (!snap.exists) return null;
  const data = snap.data();
  const uid = data?.uid;
  const productId = data?.productId;
  if (typeof uid !== 'string' || uid.length === 0) return null;
  if (
    typeof productId !== 'string' ||
    !(GOOGLE_PLAY_PRODUCT_IDS as readonly string[]).includes(productId)
  ) {
    return null;
  }
  return { uid, productId: productId as GooglePlayProductId };
}

function storedDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

async function readStoredSubscriptionRecord(
  uid: string,
): Promise<StoredSubscriptionFields | null> {
  const snap = await db.collection('subscriptions').doc(uid).get();
  if (!snap.exists) return null;
  const data = snap.data() ?? {};
  return {
    tier: typeof data.tier === 'string' ? data.tier : undefined,
    startsAt: storedDate(data.startsAt),
    expiresAt: storedDate(data.expiresAt),
    purchaseTokenHash:
      typeof data.purchaseTokenHash === 'string' ? data.purchaseTokenHash : null,
  };
}

async function lastProcessedEventTimeFromStore(
  purchaseTokenHash: string,
): Promise<number | null> {
  const snap = await db.collection(RTDN_EVENTS_COLLECTION).doc(purchaseTokenHash).get();
  const value = snap.data()?.lastEventTimeMillis;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/**
 * Advances the per-token high-watermark inside a transaction so it only ever
 * moves forward, even under a concurrent duplicate delivery.
 */
async function markProcessedInStore(
  purchaseTokenHash: string,
  eventTimeMillis: number,
  reason: string,
): Promise<void> {
  const ref = db.collection(RTDN_EVENTS_COLLECTION).doc(purchaseTokenHash);
  await db.runTransaction(async (transaction) => {
    const snap = await transaction.get(ref);
    const current = snap.data()?.lastEventTimeMillis;
    if (typeof current === 'number' && current >= eventTimeMillis) return;
    transaction.set(
      ref,
      {
        lastEventTimeMillis: eventTimeMillis,
        lastReason: reason,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  });
}

export function productionRtdnDeps(): RtdnDeps {
  const client = new AdcGooglePlaySubscriptionClient();
  return {
    providerEnabled: isSubscriptionProviderEnabled,
    resolveOwner: resolveOwnerFromRegistry,
    verify: (purchaseToken, expectedObfuscatedAccountId, now) =>
      verifyGooglePlaySubscription(client, {
        purchaseToken,
        expectedObfuscatedAccountId,
        now,
      }),
    applyEntitlement,
    readStoredSubscription: readStoredSubscriptionRecord,
    lastProcessedEventTime: lastProcessedEventTimeFromStore,
    markProcessed: markProcessedInStore,
    now: () => new Date(),
  };
}

export const handleRtdn = onMessagePublished(
  {
    topic: RTDN_TOPIC,
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    cpu: CPU_TRIGGER,
    concurrency: 1,
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    // Android Publisher access uses ADC as the dedicated verifier identity,
    // exactly like the verify callable.
    serviceAccount: GOOGLE_PLAY_RUNTIME_SERVICE_ACCOUNT,
  },
  async (event) => {
    const decoded = decodeRtdnMessageData(event.data?.message?.data);
    if (decoded === null) {
      // Poison / unknown / non-subscription payload: log and ACK. Throwing
      // here would ask Pub/Sub to redeliver a message that can never succeed.
      logger.warn('Undecodable or non-actionable RTDN message; acking', {
        messageId: event.data?.message?.messageId,
      });
      return;
    }
    // A thrown TransientRtdnError (or any unexpected throw) propagates so
    // Pub/Sub retries; every handled business case returns an outcome.
    await runRtdnNotification(decoded, productionRtdnDeps());
  },
);
