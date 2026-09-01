/**
 * Unit tests for the pure reconciliation decision
 * (functions/src/subscription/reconcile-core.ts).
 *
 * Pin the downgrade-only contract: clear privilege only when the record no
 * longer grants but the account still holds it; never add privilege; and a
 * record consistent with the account produces no write.
 */

import { describe, expect, it } from 'vitest';
import { decideReconciliation, type ReconcileRecord } from '../subscription/reconcile-core';

const grantingRecord: ReconcileRecord = {
  status: 'active',
  entitlement: 'member_monthly',
  tier: 'plus',
  platform: 'google',
  purchaseTokenHash: 'hash-1',
  startsAt: new Date('2026-08-01T00:00:00Z'),
  expiresAt: new Date('2026-09-01T00:00:00Z'),
};

const nonGrantingRecord: ReconcileRecord = {
  status: 'expired',
  entitlement: 'none',
  tier: 'plus',
  platform: 'google',
  purchaseTokenHash: 'hash-1',
  startsAt: new Date('2026-08-01T00:00:00Z'),
  expiresAt: new Date('2026-08-20T00:00:00Z'),
};

describe('decideReconciliation', () => {
  it('downgrades when a non-granting record still leaves the account privileged', () => {
    const decision = decideReconciliation('u1', nonGrantingRecord, true);
    expect(decision.action).toBe('downgrade');
    if (decision.action !== 'downgrade') throw new Error('expected downgrade');
    expect(decision.input).toMatchObject({
      userId: 'u1',
      platform: 'google',
      status: 'expired',
      entitlement: 'none',
      tier: 'plus',
      purchaseTokenHash: 'hash-1',
    });
  });

  it('is consistent when a non-granting record matches an unprivileged account', () => {
    expect(decideReconciliation('u1', nonGrantingRecord, false).action).toBe('consistent');
  });

  it('is consistent when a granting record matches a privileged account', () => {
    expect(decideReconciliation('u1', grantingRecord, true).action).toBe('consistent');
  });

  it('flags (does not fix) an under-privileged account on a granting record', () => {
    // Safe direction: never auto-grant from a record; leave for verify.
    expect(decideReconciliation('u1', grantingRecord, false).action).toBe('under_privileged');
  });

  it('treats a cancelled-before-expiry record as still granting', () => {
    const cancelled: ReconcileRecord = { ...grantingRecord, status: 'cancelled' };
    expect(decideReconciliation('u1', cancelled, true).action).toBe('consistent');
    expect(decideReconciliation('u1', cancelled, false).action).toBe('under_privileged');
  });
});
