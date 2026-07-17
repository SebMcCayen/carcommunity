/**
 * Unit tests for the member-gating switch (shared/memberGating.ts).
 *
 * These pin the two properties that matter while gating is DISABLED:
 *   1. A plain signed-in non-member passes every member gate (the unlock).
 *   2. Suspended and deleted accounts are STILL denied (the guard that must
 *      survive the unlock — PR #428 dropped exactly this by accident when the
 *      suspension check rode along inside the member check).
 *
 * If someone "simplifies" memberGateAllows to `return true`, test (2) fails.
 * That is the point.
 */

import { describe, expect, it } from 'vitest';
import { MEMBER_GATING_ENABLED, backendGateAllows, memberGateAllows } from '../shared/memberGating';
import { canAccessMemberFeatures, hasBackendAccess, type UserAccessState } from '../shared/access';

function state(overrides: Partial<UserAccessState> = {}): UserAccessState {
  return { role: 'user', activeMember: false, suspended: false, deleted: false, ...overrides };
}

describe('MEMBER_GATING_ENABLED', () => {
  it('is currently false — features are unlocked for testing', () => {
    // Flipping this to true re-locks the callable layer. If you flip it, the
    // three rules-layer switches must be flipped too (see memberGating.ts).
    expect(MEMBER_GATING_ENABLED).toBe(false);
  });
});

describe('memberGateAllows — with gating disabled', () => {
  it('lets a signed-in non-member through (this is the unlock)', () => {
    expect(memberGateAllows(state({ activeMember: false }))).toBe(true);
    // ...precisely where the old semantics would have denied them.
    expect(canAccessMemberFeatures(state({ activeMember: false }))).toBe(false);
  });

  it('still lets an active member through', () => {
    expect(memberGateAllows(state({ activeMember: true }))).toBe(true);
  });

  // ---- TEETH: the unlock must not unlock suspension/deletion --------------

  it('STILL denies a suspended non-member', () => {
    expect(memberGateAllows(state({ suspended: true }))).toBe(false);
  });

  it('STILL denies a suspended member (suspension overrides entitlement)', () => {
    expect(memberGateAllows(state({ activeMember: true, suspended: true }))).toBe(false);
  });

  it('STILL denies a suspended owner/admin', () => {
    expect(memberGateAllows(state({ role: 'owner', suspended: true }))).toBe(false);
    expect(memberGateAllows(state({ role: 'admin', suspended: true }))).toBe(false);
  });

  it('STILL denies a deleted account', () => {
    expect(memberGateAllows(state({ deleted: true }))).toBe(false);
    expect(memberGateAllows(state({ activeMember: true, deleted: true }))).toBe(false);
  });
});

describe('backendGateAllows — with gating disabled', () => {
  it('lets a signed-in non-member through', () => {
    expect(backendGateAllows(state())).toBe(true);
    expect(hasBackendAccess(state())).toBe(false);
  });

  it('lets admins and owners through (as it always did)', () => {
    expect(backendGateAllows(state({ role: 'admin' }))).toBe(true);
    expect(backendGateAllows(state({ role: 'owner' }))).toBe(true);
  });

  // ---- TEETH -------------------------------------------------------------

  it('STILL denies suspended callers of every role', () => {
    expect(backendGateAllows(state({ suspended: true }))).toBe(false);
    expect(backendGateAllows(state({ role: 'admin', suspended: true }))).toBe(false);
    expect(backendGateAllows(state({ role: 'owner', activeMember: true, suspended: true }))).toBe(
      false,
    );
  });

  it('STILL denies deleted callers of every role', () => {
    expect(backendGateAllows(state({ deleted: true }))).toBe(false);
    expect(backendGateAllows(state({ role: 'owner', deleted: true }))).toBe(false);
  });
});

describe('the underlying semantics are preserved for re-locking', () => {
  it('canAccessMemberFeatures/hasBackendAccess are untouched by the switch', () => {
    // The switch bypasses these helpers; it does not redefine them. Re-locking
    // restores exactly this behaviour at every call site.
    expect(canAccessMemberFeatures(state({ activeMember: true }))).toBe(true);
    expect(canAccessMemberFeatures(state({ activeMember: false }))).toBe(false);
    expect(canAccessMemberFeatures(state({ activeMember: true, suspended: true }))).toBe(false);
    expect(hasBackendAccess(state({ role: 'admin' }))).toBe(true);
    expect(hasBackendAccess(state({ role: 'user', activeMember: false }))).toBe(false);
  });
});
