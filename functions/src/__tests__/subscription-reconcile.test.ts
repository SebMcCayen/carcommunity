/**
 * Unit tests for the reconciliation sweep orchestration
 * (functions/src/subscription/reconcile.ts::runSubscriptionReconciliation).
 *
 * Dependencies (provider gate, cursor, page query, Auth/flag privilege read,
 * applyEntitlement) are injected, so these run with no emulator. They pin:
 * provider-gated skip, drift downgrade, per-user fail isolation, orphan
 * handling, and the rotating cursor's wrap behaviour.
 */

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EntitlementRecordInput } from '../subscription/subscription-core';
import { MAX_RECONCILE_PER_RUN, type ReconcileRecord } from '../subscription/reconcile-core';
import type { ReconcileCandidate, ReconcileDeps } from '../subscription/reconcile';

let mod: typeof import('../subscription/reconcile');

beforeAll(async () => {
  process.env.GCLOUD_PROJECT ??= 'demo-test';
  process.env.FIREBASE_CONFIG ??= JSON.stringify({
    projectId: 'demo-test',
    databaseURL: 'https://demo-test.firebaseio.com',
    storageBucket: 'demo-test.appspot.com',
  });
  mod = await import('../subscription/reconcile');
});

const granting: ReconcileRecord = {
  status: 'active',
  entitlement: 'member_monthly',
  tier: 'plus',
  platform: 'google',
  purchaseTokenHash: 'h',
  startsAt: null,
  expiresAt: new Date('2026-09-01T00:00:00Z'),
};

const nonGranting: ReconcileRecord = {
  status: 'expired',
  entitlement: 'none',
  tier: 'plus',
  platform: 'google',
  purchaseTokenHash: 'h',
  startsAt: null,
  expiresAt: new Date('2026-08-20T00:00:00Z'),
};

let applied: EntitlementRecordInput[];
let cursorWrites: string[];

function makeDeps(
  page: ReconcileCandidate[],
  overrides: Partial<ReconcileDeps> = {},
): ReconcileDeps {
  return {
    providerEnabled: vi.fn(async () => true),
    readCursor: vi.fn(async () => ''),
    writeCursor: vi.fn(async (afterId: string) => {
      cursorWrites.push(afterId);
    }),
    queryPage: vi.fn(async () => page),
    userPrivilege: vi.fn(async () => ({ exists: true, holds: true })),
    reconcileBadge: vi.fn(async () => {}),
    applyEntitlement: vi.fn(async (input: EntitlementRecordInput) => {
      applied.push(input);
    }),
    ...overrides,
  };
}

beforeEach(() => {
  applied = [];
  cursorWrites = [];
});

describe('runSubscriptionReconciliation', () => {
  it('is a skipped no-op while the provider is disabled', async () => {
    const deps = makeDeps([{ id: 'u1', record: nonGranting }], {
      providerEnabled: vi.fn(async () => false),
    });
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.skipped).toBe(true);
    expect(deps.queryPage).not.toHaveBeenCalled();
    expect(applied).toEqual([]);
  });

  it('clears stale privilege for an over-privileged, non-granting record', async () => {
    const deps = makeDeps([{ id: 'u1', record: nonGranting }]);
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.reconciledCount).toBe(1);
    expect(result.reconciledUids).toEqual(['u1']);
    expect(applied[0]).toMatchObject({ userId: 'u1', status: 'expired', entitlement: 'none' });
  });

  it('leaves a consistent granting record untouched', async () => {
    const deps = makeDeps([{ id: 'u1', record: granting }]);
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.consistentCount).toBe(1);
    expect(result.reconciledCount).toBe(0);
    expect(applied).toEqual([]);
    expect(deps.reconcileBadge).toHaveBeenCalledWith('u1');
  });

  it('repairs cosmetic projection for Supporter and Plus even when privilege is unchanged', async () => {
    const deps = makeDeps([
      { id: 'supporter', record: { ...granting, tier: 'supporter' } },
      { id: 'plus', record: granting },
    ]);
    await mod.runSubscriptionReconciliation(deps);
    expect(deps.reconcileBadge).toHaveBeenCalledWith('supporter');
    expect(deps.reconcileBadge).toHaveBeenCalledWith('plus');
    expect(applied).toEqual([]);
  });

  it('does not let a cosmetic repair failure block privilege removal', async () => {
    const deps = makeDeps([{ id: 'u1', record: nonGranting }], {
      reconcileBadge: vi.fn(async () => {
        throw new Error('badge write failed');
      }),
    });
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.reconciledCount).toBe(1);
    expect(applied[0]).toMatchObject({ userId: 'u1', entitlement: 'none' });
  });

  it('flags but does not fix an under-privileged member', async () => {
    const deps = makeDeps([{ id: 'u1', record: granting }], {
      userPrivilege: vi.fn(async () => ({ exists: true, holds: false })),
    });
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.underPrivilegedCount).toBe(1);
    expect(applied).toEqual([]);
  });

  it('counts an orphaned record (no Auth account) without mutating', async () => {
    const deps = makeDeps([{ id: 'u1', record: nonGranting }], {
      userPrivilege: vi.fn(async () => ({ exists: false, holds: false })),
    });
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.orphanedCount).toBe(1);
    expect(applied).toEqual([]);
  });

  it('isolates a per-user failure and keeps processing the batch', async () => {
    const userPrivilege = vi
      .fn<ReconcileDeps['userPrivilege']>()
      .mockRejectedValueOnce(new Error('auth blip'))
      .mockResolvedValue({ exists: true, holds: true });
    const deps = makeDeps(
      [
        { id: 'u1', record: nonGranting },
        { id: 'u2', record: nonGranting },
      ],
      { userPrivilege },
    );
    const result = await mod.runSubscriptionReconciliation(deps);
    expect(result.failedCount).toBe(1);
    expect(result.reconciledCount).toBe(1);
    expect(result.reconciledUids).toEqual(['u2']);
  });

  it('wraps the cursor to the start on a short (end-of-collection) page', async () => {
    const deps = makeDeps([{ id: 'u1', record: granting }]);
    await mod.runSubscriptionReconciliation(deps);
    expect(cursorWrites).toEqual(['']);
  });

  it('advances the cursor to the last id on a full page', async () => {
    const fullPage: ReconcileCandidate[] = Array.from(
      { length: MAX_RECONCILE_PER_RUN },
      (_, i) => ({
        id: `u${i}`,
        record: granting,
      }),
    );
    const deps = makeDeps(fullPage);
    await mod.runSubscriptionReconciliation(deps);
    expect(cursorWrites).toEqual([`u${MAX_RECONCILE_PER_RUN - 1}`]);
  });
});
