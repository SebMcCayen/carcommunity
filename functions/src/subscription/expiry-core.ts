/**
 * Subscription expiry sweep — pure decision logic.
 *
 * `subscriptions/{uid}.expiresAt` was written from day one (by
 * subscription.grantEntitlement, and by the store adapter when one is
 * wired) but read by NOTHING: an entitlement — including a manually
 * granted one — never lapsed. This module is the decision half of the
 * sweep that closes that hole; subscription/scheduled.ts is the I/O half.
 *
 * WHAT `expiresAt` MEANS. It is the instant the paid period ends — the
 * renewal boundary, not a deadline the member has already blown. Both
 * `active` and `grace_period` grant access (isSubscriptionActiveStatus),
 * and `grace_period` is the STORE's own signal that it is retrying a
 * failed charge. So a document sitting past its `expiresAt` is the normal
 * state of every subscription for the few minutes-to-hours between the
 * period ending and the renewal (or the store's status push) landing.
 * Revoking on that boundary would lock paying members out routinely.
 *
 * THE GRACE WINDOW. SUBSCRIPTION_EXPIRY_GRACE_HOURS (72 h) is the delay
 * between `expiresAt` passing and the sweep acting. It is sized off the
 * failure modes that actually delay a renewal, not off a round number:
 *
 *  - Store-to-backend notification latency. Play/App Store status pushes
 *    are retried asynchronously and can be hours late; a renewal that
 *    succeeded at the store but has not reached us yet is
 *    indistinguishable here from a lapse.
 *  - Billing retry. Both stores retry a soft-declined card for days
 *    before declaring the subscription lapsed. Those members are still
 *    paying customers mid-retry.
 *  - Our own sweep cadence and any deploy freeze — 72 h survives a long
 *    weekend without a human in the loop.
 *
 * The asymmetry is deliberate and is the whole argument: revoking 72 h
 * late costs us three days of access we were not owed, while revoking
 * 1 h early takes away access a member PAID for and produces a support
 * ticket. Erring long is strictly cheaper. It is a named constant so the
 * number can be shortened once real store adapters (which push an
 * authoritative `expired` status of their own, making this sweep a
 * backstop rather than the primary path) are wired.
 *
 * Pure module — no Firebase Admin SDK imports, no clock reads.
 */

import {
  SUBSCRIPTION_PLATFORMS,
  SUBSCRIPTION_STATUSES,
  isSubscriptionActiveStatus,
  type SubscriptionPlatform,
  type SubscriptionStatus,
} from './subscription-core';

/** Hours after `expiresAt` before the sweep revokes. See the header. */
export const SUBSCRIPTION_EXPIRY_GRACE_HOURS = 72;

/**
 * Upper bound of revocations per sweep run — keeps a backlog from pushing
 * the scheduled run past its timeout, exactly like MAX_PURGES_PER_RUN in
 * account/scheduled.ts. The sweep drains any remainder on later runs
 * (oldest lapse first), so this bounds LATENCY, never correctness.
 *
 * Throughput headroom: at the 3-hourly cadence this is 1 600 revocations
 * per day. A 20 000-member base on monthly subscriptions churns on the
 * order of 700 lapses/day even if every single member lapsed once a
 * month, so there is >2x headroom; and because each revocation makes its
 * document stop matching the query, a backlog drains rather than
 * recirculates.
 */
export const MAX_EXPIRIES_PER_RUN = 200;

/**
 * The statuses the sweep considers, DERIVED from the granting predicate
 * rather than hardcoded: the sweep's job is to revoke things that
 * currently grant access, so if isSubscriptionActiveStatus ever changes,
 * the sweep's query follows it instead of silently going blind to a new
 * granting status. Today: ['active', 'grace_period'].
 *
 * Restricting the query to these is also what makes the sweep SCALE — an
 * `expiresAt <= cutoff` query alone would match every historically
 * expired document forever, so the page would fill with already-revoked
 * records and never reach a new lapse.
 */
export const EXPIRY_SWEEP_STATUSES: readonly SubscriptionStatus[] =
  SUBSCRIPTION_STATUSES.filter(isSubscriptionActiveStatus);

/**
 * The instant a subscription must have expired BEFORE to be swept — i.e.
 * `now` minus the grace window.
 */
export function subscriptionExpiryCutoff(now: Date): Date {
  return new Date(now.getTime() - SUBSCRIPTION_EXPIRY_GRACE_HOURS * 60 * 60 * 1000);
}

/** The subset of a `subscriptions/{uid}` document the decision reads. */
export interface SubscriptionExpiryFields {
  status?: unknown;
  entitlement?: unknown;
  platform?: unknown;
  purchaseTokenHash?: unknown;
  /** Already converted from a Firestore Timestamp by the caller. */
  expiresAt?: Date | null;
}

export type SubscriptionExpiryDecision =
  | {
      expire: true;
      /** The lapse instant — the document's own `expiresAt`. */
      expiresAt: Date;
      previousStatus: SubscriptionStatus;
      platform: SubscriptionPlatform;
      purchaseTokenHash: string | null;
    }
  | {
      expire: false;
      reason: 'not_granting' | 'no_expiry' | 'within_grace';
    };

function toStatus(value: unknown): SubscriptionStatus | null {
  return typeof value === 'string' && (SUBSCRIPTION_STATUSES as readonly string[]).includes(value)
    ? (value as SubscriptionStatus)
    : null;
}

/**
 * Re-derives the expire/skip decision from the document itself.
 *
 * The scheduled query already filters on status + expiresAt, so this is
 * belt-and-braces — but it is load-bearing belt-and-braces: it is the
 * only thing standing between a mis-deployed or mis-ordered index and a
 * WRONGFUL REVOCATION, and it is what the unit tests pin. It never
 * consults a clock; the caller passes the cutoff.
 *
 * A document with NO `expiresAt` is never swept ('no_expiry'). That is
 * the perpetual manual grant — see the scheduled module's header for why
 * that case must stay untouched.
 */
export function decideSubscriptionExpiry(
  fields: SubscriptionExpiryFields,
  cutoff: Date,
): SubscriptionExpiryDecision {
  const status = toStatus(fields.status);
  if (status === null || !isSubscriptionActiveStatus(status)) {
    // Already expired/revoked/cancelled/inactive — nothing to take away.
    // This is the idempotent no-op path: a re-run sees its own output.
    return { expire: false, reason: 'not_granting' };
  }
  const expiresAt = fields.expiresAt;
  if (!(expiresAt instanceof Date) || Number.isNaN(expiresAt.getTime())) {
    return { expire: false, reason: 'no_expiry' };
  }
  if (expiresAt.getTime() > cutoff.getTime()) {
    return { expire: false, reason: 'within_grace' };
  }
  const platform =
    typeof fields.platform === 'string' &&
    (SUBSCRIPTION_PLATFORMS as readonly string[]).includes(fields.platform)
      ? (fields.platform as SubscriptionPlatform)
      : 'manual';
  return {
    expire: true,
    expiresAt,
    previousStatus: status,
    platform,
    // Preserved verbatim: applyEntitlement rewrites the document wholesale
    // (merge-less batch.set), so anything not carried through is DESTROYED.
    // The hash is the only record of which purchase this entitlement came
    // from; losing it would orphan the record from its receipt.
    purchaseTokenHash:
      typeof fields.purchaseTokenHash === 'string' ? fields.purchaseTokenHash : null,
  };
}

/**
 * Deterministic notification ID for one lapse, keyed on the lapse instant
 * rather than the sweep run: a replayed sweep — or a second revocation
 * attempt after a partial failure — collapses onto the same inbox item
 * instead of stacking duplicates. A member who renews and later lapses
 * again has a different `expiresAt`, so they are correctly notified again.
 *
 * The uid is deliberately absent: the item already lives under
 * notifications/{uid}/items, so repeating it would only surface the uid in
 * the inbox (same reasoning as the inactivity warning's ID).
 */
export function subscriptionExpiredNotificationId(expiresAt: Date): string {
  return `subscription-expired-${expiresAt.getTime()}`;
}
