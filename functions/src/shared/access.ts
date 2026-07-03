/**
 * Canonical access-decision helpers for Cloud Functions.
 *
 * Ports the semantics of packages/shared/src/users.ts to the Firestore target
 * model (docs/firebase-data-model.md), where the legacy status enum
 * ('active' | 'warned' | 'temporarily_suspended' | 'permanently_suspended' |
 * 'deleted') is simplified to the backend-managed boolean flags `suspended`
 * and `deleted`, and the legacy subscriptionEntitlement enum
 * ('none' | 'member_monthly') is simplified to the boolean `activeMember`
 * (see docs/migration/backend-domain-mapping.md, "Prisma user data →
 * Firestore user documents").
 *
 * Invariants preserved from packages/shared/src/users.ts:
 * - Suspension always overrides subscription entitlement.
 * - Deleted users have no access to anything.
 * - Admin and owner roles do not require a member subscription for admin
 *   access, but suspension/deletion still revokes admin access.
 *
 * Pure module — no Firebase Admin SDK imports — so it stays unit-testable
 * without emulators.
 */

/** Mirrors contracts/schemas/common.schema.json #/$defs/userRole. */
export const USER_ROLES = ['user', 'admin', 'owner'] as const;
export type UserRole = (typeof USER_ROLES)[number];

/**
 * Backend-managed access state stored on `users/{uid}`
 * (docs/firebase-data-model.md). All four fields are client-immutable.
 */
export interface UserAccessState {
  role: UserRole;
  activeMember: boolean;
  suspended: boolean;
  deleted: boolean;
}

const ADMIN_BYPASS_ROLE_SET: ReadonlySet<UserRole> = new Set(['admin', 'owner']);

export function isUserRole(value: unknown): value is UserRole {
  return typeof value === 'string' && (USER_ROLES as readonly string[]).includes(value);
}

export function isAdminRole(role: UserRole): boolean {
  return role === 'admin';
}

export function isOwnerRole(role: UserRole): boolean {
  return role === 'owner';
}

/**
 * Equivalent of `isSuspendedStatus(status) || status === 'deleted'` in
 * packages/shared/src/users.ts — the state in which every feature gate closes.
 */
export function isRestricted(input: Pick<UserAccessState, 'suspended' | 'deleted'>): boolean {
  return input.suspended === true || input.deleted === true;
}

/**
 * Port of canAccessMemberFeatures: requires the activeMember entitlement and
 * a non-suspended, non-deleted account. Suspension always overrides
 * entitlement.
 */
export function canAccessMemberFeatures(input: UserAccessState): boolean {
  if (isRestricted(input)) {
    return false;
  }
  return input.activeMember === true;
}

/**
 * Port of canAccessAdminFeatures: requires admin or owner role and a
 * non-suspended, non-deleted account. Admin access never requires a member
 * subscription.
 */
export function canAccessAdminFeatures(input: Pick<UserAccessState, 'role' | 'suspended' | 'deleted'>): boolean {
  if (isRestricted(input)) {
    return false;
  }
  return ADMIN_BYPASS_ROLE_SET.has(input.role);
}

/**
 * Port of hasBackendAccess: admins/owners always pass (unless restricted);
 * everyone else needs the activeMember entitlement.
 */
export function hasBackendAccess(input: UserAccessState): boolean {
  if (isRestricted(input)) {
    return false;
  }
  if (ADMIN_BYPASS_ROLE_SET.has(input.role)) {
    return true;
  }
  return input.activeMember === true;
}

/**
 * Reads the backend-managed access state from a `users/{uid}` document,
 * applying safe defaults for missing fields (a document without a role is
 * treated as a plain, unentitled user — never as an admin or member).
 */
export function toUserAccessState(doc: Record<string, unknown> | undefined): UserAccessState {
  return {
    role: isUserRole(doc?.role) ? doc.role : 'user',
    activeMember: doc?.activeMember === true,
    suspended: doc?.suspended === true,
    deleted: doc?.deleted === true,
  };
}
