/**
 * Unit tests for the subscription expiry sweep's pure decision logic
 * (functions/src/subscription/expiry-core.ts).
 *
 * These pin the two properties that keep the sweep from wrongly revoking a
 * paying member: the grace window is actually applied, and a subscription
 * with no `expiresAt` (the perpetual manual grant) is never swept.
 */

import { describe, expect, it } from 'vitest';
import {
  SUBSCRIPTION_STATUSES,
  isSubscriptionActiveStatus,
  type SubscriptionStatus,
} from '../subscription/subscription-core';
import {
  EXPIRY_SWEEP_STATUSES,
  MAX_EXPIRIES_PER_RUN,
  SUBSCRIPTION_EXPIRY_GRACE_HOURS,
  decideSubscriptionExpiry,
  subscriptionExpiredNotificationId,
  subscriptionExpiryCutoff,
} from '../subscription/expiry-core';

const NOW = new Date('2026-07-30T12:00:00.000Z');
const CUTOFF = subscriptionExpiryCutoff(NOW);
const HOUR_MS = 60 * 60 * 1000;

function hoursAgo(hours: number): Date {
  return new Date(NOW.getTime() - hours * HOUR_MS);
}

describe('subscription expiry constants', () => {
  it('applies a 72-hour grace window', () => {
    expect(SUBSCRIPTION_EXPIRY_GRACE_HOURS).toBe(72);
    expect(CUTOFF.toISOString()).toBe('2026-07-27T12:00:00.000Z');
  });

  it('bounds each run', () => {
    expect(MAX_EXPIRIES_PER_RUN).toBeGreaterThan(0);
    expect(MAX_EXPIRIES_PER_RUN).toBeLessThanOrEqual(500);
  });

  /**
   * The sweep's status filter is DERIVED from the granting predicate rather
   * than hardcoded. If a future status is added that grants access, this
   * assertion is what proves the sweep picked it up instead of silently
   * going blind to it.
   */
  it('sweeps exactly the statuses that grant access', () => {
    expect([...EXPIRY_SWEEP_STATUSES]).toEqual(['active', 'grace_period']);
    for (const status of SUBSCRIPTION_STATUSES) {
      expect(EXPIRY_SWEEP_STATUSES.includes(status)).toBe(isSubscriptionActiveStatus(status));
    }
  });
});

describe('decideSubscriptionExpiry', () => {
  const granting = {
    status: 'active' as SubscriptionStatus,
    entitlement: 'member_monthly',
    platform: 'manual',
    purchaseTokenHash: null,
  };

  it('expires a subscription that lapsed beyond the grace window', () => {
    const expiresAt = hoursAgo(SUBSCRIPTION_EXPIRY_GRACE_HOURS + 1);
    const decision = decideSubscriptionExpiry({ ...granting, expiresAt }, CUTOFF);
    expect(decision).toEqual({
      expire: true,
      expiresAt,
      previousStatus: 'active',
      platform: 'manual',
      purchaseTokenHash: null,
    });
  });

  it('leaves a subscription still INSIDE the grace window alone', () => {
    // The renewal-latency case: expired an hour ago, almost certainly
    // renewing. Revoking here locks out a paying member.
    const decision = decideSubscriptionExpiry({ ...granting, expiresAt: hoursAgo(1) }, CUTOFF);
    expect(decision).toEqual({ expire: false, reason: 'within_grace' });
  });

  it('leaves a subscription one hour short of the grace boundary alone', () => {
    const decision = decideSubscriptionExpiry(
      { ...granting, expiresAt: hoursAgo(SUBSCRIPTION_EXPIRY_GRACE_HOURS - 1) },
      CUTOFF,
    );
    expect(decision).toEqual({ expire: false, reason: 'within_grace' });
  });

  it('expires exactly ON the grace boundary (the query bound is inclusive)', () => {
    const decision = decideSubscriptionExpiry({ ...granting, expiresAt: CUTOFF }, CUTOFF);
    expect(decision.expire).toBe(true);
  });

  it('leaves a not-yet-expired subscription alone', () => {
    const decision = decideSubscriptionExpiry(
      { ...granting, expiresAt: new Date(NOW.getTime() + 10 * 24 * HOUR_MS) },
      CUTOFF,
    );
    expect(decision).toEqual({ expire: false, reason: 'within_grace' });
  });

  /**
   * The perpetual manual grant — the operational path today, and the
   * mechanism behind the operator's own admin/test access. The sweep is
   * driven by an explicit expired `expiresAt`, never by absence of
   * evidence.
   */
  it('never expires a subscription with no expiresAt', () => {
    expect(decideSubscriptionExpiry({ ...granting, expiresAt: null }, CUTOFF)).toEqual({
      expire: false,
      reason: 'no_expiry',
    });
    expect(decideSubscriptionExpiry({ ...granting }, CUTOFF)).toEqual({
      expire: false,
      reason: 'no_expiry',
    });
  });

  it('never expires an unparseable expiresAt', () => {
    expect(
      decideSubscriptionExpiry({ ...granting, expiresAt: new Date('nonsense') }, CUTOFF),
    ).toEqual({ expire: false, reason: 'no_expiry' });
  });

  it('is a no-op for every non-granting status (idempotent re-run)', () => {
    const lapsed = hoursAgo(SUBSCRIPTION_EXPIRY_GRACE_HOURS * 10);
    for (const status of SUBSCRIPTION_STATUSES.filter((s) => !isSubscriptionActiveStatus(s))) {
      expect(decideSubscriptionExpiry({ ...granting, status, expiresAt: lapsed }, CUTOFF)).toEqual({
        expire: false,
        reason: 'not_granting',
      });
    }
  });

  it('rejects an unknown status rather than guessing', () => {
    expect(
      decideSubscriptionExpiry(
        { ...granting, status: 'totally_new_status', expiresAt: hoursAgo(999) },
        CUTOFF,
      ),
    ).toEqual({ expire: false, reason: 'not_granting' });
  });

  it('sweeps grace_period too — the store is retrying, but not forever', () => {
    const expiresAt = hoursAgo(SUBSCRIPTION_EXPIRY_GRACE_HOURS + 5);
    const decision = decideSubscriptionExpiry(
      { ...granting, status: 'grace_period', platform: 'google', expiresAt },
      CUTOFF,
    );
    expect(decision).toMatchObject({ expire: true, previousStatus: 'grace_period', platform: 'google' });
  });

  /**
   * applyEntitlement rewrites subscriptions/{uid} WHOLESALE (merge-less
   * batch.set), so anything the decision does not carry through is
   * destroyed. The token hash is the only link back to the purchase.
   */
  it('carries the purchase token hash and platform through', () => {
    const decision = decideSubscriptionExpiry(
      {
        ...granting,
        platform: 'apple',
        purchaseTokenHash: 'a'.repeat(64),
        expiresAt: hoursAgo(200),
      },
      CUTOFF,
    );
    expect(decision).toMatchObject({
      expire: true,
      platform: 'apple',
      purchaseTokenHash: 'a'.repeat(64),
    });
  });

  it('falls back to the manual platform when the stored platform is unusable', () => {
    const decision = decideSubscriptionExpiry(
      { ...granting, platform: 'nintendo', expiresAt: hoursAgo(200) },
      CUTOFF,
    );
    expect(decision).toMatchObject({ expire: true, platform: 'manual' });
  });

  it('drops a non-string purchase token hash rather than storing garbage', () => {
    const decision = decideSubscriptionExpiry(
      { ...granting, purchaseTokenHash: 12345, expiresAt: hoursAgo(200) },
      CUTOFF,
    );
    expect(decision).toMatchObject({ expire: true, purchaseTokenHash: null });
  });
});

describe('subscriptionExpiredNotificationId', () => {
  it('is deterministic for one lapse instant, so a replay cannot duplicate', () => {
    const expiresAt = new Date('2026-07-01T00:00:00.000Z');
    expect(subscriptionExpiredNotificationId(expiresAt)).toBe(
      subscriptionExpiredNotificationId(new Date(expiresAt.getTime())),
    );
  });

  it('differs for a later lapse, so a renew-then-lapse notifies again', () => {
    expect(subscriptionExpiredNotificationId(new Date('2026-07-01T00:00:00.000Z'))).not.toBe(
      subscriptionExpiredNotificationId(new Date('2026-08-01T00:00:00.000Z')),
    );
  });

  it('does not repeat the uid (the inbox is already per-user)', () => {
    expect(subscriptionExpiredNotificationId(new Date(0))).toBe('subscription-expired-0');
  });
});
