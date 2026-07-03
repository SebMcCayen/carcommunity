/**
 * Unit tests for functions/src/shared/access.ts — the port of the canonical
 * access-decision semantics from packages/shared/src/users.ts to the
 * Firestore boolean model. Each invariant asserted here mirrors a documented
 * behaviour of the shared package.
 */

import { describe, expect, it } from 'vitest';
import {
  canAccessAdminFeatures,
  canAccessMemberFeatures,
  hasBackendAccess,
  isAdminRole,
  isOwnerRole,
  isRestricted,
  isUserRole,
  toUserAccessState,
  type UserAccessState,
} from '../shared/access';

function state(overrides: Partial<UserAccessState> = {}): UserAccessState {
  return { role: 'user', activeMember: false, suspended: false, deleted: false, ...overrides };
}

describe('role helpers', () => {
  it('isAdminRole matches only admin (owner is a distinct elevated role)', () => {
    expect(isAdminRole('admin')).toBe(true);
    expect(isAdminRole('owner')).toBe(false);
    expect(isAdminRole('user')).toBe(false);
  });

  it('isOwnerRole matches only owner', () => {
    expect(isOwnerRole('owner')).toBe(true);
    expect(isOwnerRole('admin')).toBe(false);
  });

  it('isUserRole validates against the contract enum', () => {
    expect(isUserRole('user')).toBe(true);
    expect(isUserRole('admin')).toBe(true);
    expect(isUserRole('owner')).toBe(true);
    expect(isUserRole('superadmin')).toBe(false);
    expect(isUserRole(42)).toBe(false);
    expect(isUserRole(undefined)).toBe(false);
  });
});

describe('isRestricted', () => {
  it('is true when suspended or deleted', () => {
    expect(isRestricted(state({ suspended: true }))).toBe(true);
    expect(isRestricted(state({ deleted: true }))).toBe(true);
    expect(isRestricted(state())).toBe(false);
  });
});

describe('canAccessMemberFeatures', () => {
  it('requires the activeMember entitlement', () => {
    expect(canAccessMemberFeatures(state({ activeMember: true }))).toBe(true);
    expect(canAccessMemberFeatures(state({ activeMember: false }))).toBe(false);
  });

  it('suspension always overrides entitlement', () => {
    expect(canAccessMemberFeatures(state({ activeMember: true, suspended: true }))).toBe(false);
  });

  it('deleted users never have member access', () => {
    expect(canAccessMemberFeatures(state({ activeMember: true, deleted: true }))).toBe(false);
  });

  it('admin role alone does not grant member features', () => {
    expect(canAccessMemberFeatures(state({ role: 'admin' }))).toBe(false);
  });
});

describe('canAccessAdminFeatures', () => {
  it('grants access to admin and owner roles without a member subscription', () => {
    expect(canAccessAdminFeatures(state({ role: 'admin' }))).toBe(true);
    expect(canAccessAdminFeatures(state({ role: 'owner' }))).toBe(true);
    expect(canAccessAdminFeatures(state({ role: 'user' }))).toBe(false);
  });

  it('suspension revokes admin access', () => {
    expect(canAccessAdminFeatures(state({ role: 'admin', suspended: true }))).toBe(false);
    expect(canAccessAdminFeatures(state({ role: 'owner', suspended: true }))).toBe(false);
  });

  it('deletion revokes admin access', () => {
    expect(canAccessAdminFeatures(state({ role: 'admin', deleted: true }))).toBe(false);
  });
});

describe('hasBackendAccess', () => {
  it('admins and owners bypass the entitlement requirement', () => {
    expect(hasBackendAccess(state({ role: 'admin' }))).toBe(true);
    expect(hasBackendAccess(state({ role: 'owner' }))).toBe(true);
  });

  it('plain users need the activeMember entitlement', () => {
    expect(hasBackendAccess(state({ activeMember: true }))).toBe(true);
    expect(hasBackendAccess(state())).toBe(false);
  });

  it('suspension and deletion override everything, including admin bypass', () => {
    expect(hasBackendAccess(state({ role: 'owner', suspended: true }))).toBe(false);
    expect(hasBackendAccess(state({ role: 'admin', deleted: true }))).toBe(false);
    expect(hasBackendAccess(state({ activeMember: true, suspended: true }))).toBe(false);
  });
});

describe('toUserAccessState', () => {
  it('reads backend-managed fields from a users/{uid} document', () => {
    expect(
      toUserAccessState({ role: 'admin', activeMember: true, suspended: false, deleted: false }),
    ).toEqual({ role: 'admin', activeMember: true, suspended: false, deleted: false });
  });

  it('applies safe defaults for a missing document (never admin, never member)', () => {
    expect(toUserAccessState(undefined)).toEqual({
      role: 'user',
      activeMember: false,
      suspended: false,
      deleted: false,
    });
  });

  it('treats malformed values as unprivileged rather than trusting them', () => {
    expect(
      toUserAccessState({ role: 'superadmin', activeMember: 'yes', suspended: 1, deleted: null }),
    ).toEqual({ role: 'user', activeMember: false, suspended: false, deleted: false });
  });
});
