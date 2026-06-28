export const USER_ROLES = ['user', 'admin', 'owner'] as const;
export type UserRole = (typeof USER_ROLES)[number];

export const USER_STATUSES = [
  'active',
  'warned',
  'temporarily_suspended',
  'permanently_suspended',
  'deleted',
] as const;
export type UserStatus = (typeof USER_STATUSES)[number];

export const SUBSCRIPTION_ENTITLEMENTS = ['none', 'member_monthly'] as const;
export type SubscriptionEntitlement = (typeof SUBSCRIPTION_ENTITLEMENTS)[number];

export const MODERATION_ACTION_TYPES = [
  'warning',
  'temporary_suspension',
  'permanent_suspension',
  'restriction',
  'restore_access',
] as const;
export type ModerationActionType = (typeof MODERATION_ACTION_TYPES)[number];

export interface UserSummary {
  id: string;
  displayName: string | null;
  email: string | null;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  lastActiveAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Minimum non-sensitive user data required for backend and shared access checks.
 * Use this instead of full user records when only role/status/entitlement are needed.
 */
export type SafeAccessUserSummary = Pick<UserSummary, 'role' | 'status' | 'subscriptionEntitlement'>;

export interface OnboardingStatus {
  onboardingCompletedAt: string | null;
  ageConfirmedAt: string | null;
  termsAcceptedAt: string | null;
  privacyPolicyAcceptedAt: string | null;
}

export interface AuditLogSummary {
  id: string;
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  reason: string | null;
  createdAt: string;
}

export interface ModerationRequest {
  reason: string;
  /** Required for temporary_suspension actions only. ISO 8601 datetime string. */
  expiresAt?: string;
}

export interface ModerationActionSummary {
  id: string;
  targetUserId: string;
  actorUserId: string | null;
  actionType: ModerationActionType;
  reason: string;
  createdAt: string;
  expiresAt: string | null;
}

export interface ModerationResponse {
  ok: true;
  data: {
    action: ModerationActionSummary;
  };
}

/** Admin-facing moderation summary for a user. Does not expose sensitive session data. */
export interface AdminUserModerationSummary {
  userId: string;
  status: UserStatus;
  recentActions: ModerationActionSummary[];
}

export interface AuditLogListResponse {
  ok: true;
  data: {
    entries: AuditLogSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface CurrentUserResponse {
  ok: true;
  data: {
    user: {
      id: string;
      displayName: string | null;
      role: UserRole;
      status: UserStatus;
      subscriptionEntitlement: SubscriptionEntitlement;
      lastActiveAt: string | null;
      onboarding: OnboardingStatus;
    };
  };
}

export interface AdminUsersResponse {
  ok: true;
  data: {
    users: UserSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

const SUSPENDED_USER_STATUS_SET = new Set<UserStatus>(['temporarily_suspended', 'permanently_suspended']);
const ADMIN_BYPASS_ROLE_SET = new Set<UserRole>(['admin', 'owner']);

export function isSuspendedStatus(status: UserStatus): boolean {
  return SUSPENDED_USER_STATUS_SET.has(status);
}

export function isAdminRole(role: UserRole): boolean {
  return role === 'admin';
}

export function isOwnerRole(role: UserRole): boolean {
  return role === 'owner';
}

export function hasMemberEntitlement(subscriptionEntitlement: SubscriptionEntitlement): boolean {
  return subscriptionEntitlement === 'member_monthly';
}

/**
 * Returns true if the user can access member-only features.
 * Requires an active member_monthly subscription and a non-suspended, non-deleted status.
 * Suspension always overrides subscription entitlement.
 */
export function canAccessMemberFeatures(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }
  return hasMemberEntitlement(input.subscriptionEntitlement);
}

/**
 * Returns true if the user can access admin features.
 * Requires admin or owner role and a non-suspended, non-deleted status.
 * Admin and owner roles do not require a member subscription for admin access.
 */
export function canAccessAdminFeatures(input: { role: UserRole; status: UserStatus }): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }
  return ADMIN_BYPASS_ROLE_SET.has(input.role);
}

/**
 * Returns true if the user can start or maintain a live location sharing session.
 * All non-suspended, non-deleted users may share their own location regardless of subscription.
 *
 * TODO: Add blocking check once the blocking graph is available — a blocked user may
 *   not be permitted to share their location with specific users.
 */
export function canShareOwnLiveLocation(
  input: Pick<UserSummary, 'status'> | SafeAccessUserSummary,
): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }
  return true;
}

export function canViewOtherLiveLocations(input: SafeAccessUserSummary): boolean {
  return canAccessMemberFeatures(input);
}

export function canAccessLiveLocationAdminSummary(input: SafeAccessUserSummary): boolean {
  return canAccessAdminFeatures(input);
}

/**
 * Returns true if the user can access garage features (Mitt garage).
 * Requires an active member_monthly subscription and a non-suspended, non-deleted status.
 * Suspension always overrides subscription entitlement.
 */
export function canAccessGarage(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  return canAccessMemberFeatures(input);
}

/**
 * Returns true if the user can view safe offer teasers.
 * Active non-suspended, non-deleted users may view teasers.
 */
export function canViewPartnerOfferTeaser(input: { role: UserRole; status: UserStatus }): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') return false;
  return true;
}

/**
 * Returns true if the user can view full protected partner offer details.
 * Requires active member_monthly entitlement. Suspension overrides entitlement.
 */
export function canViewPartnerOfferDetails(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  return canAccessMemberFeatures(input);
}

/**
 * Returns true if the user can manage partner offers (admin/owner only).
 */
export function canManagePartnerOffers(input: { role: UserRole; status: UserStatus }): boolean {
  return canAccessAdminFeatures(input);
}

/**
 * Returns true if the offer is currently available based on server time.
 * availableFrom and availableUntil are optional ISO strings.
 */
export function isPartnerOfferCurrentlyAvailable(input: {
  availableFrom: string | null;
  availableUntil: string | null;
  now?: Date;
}): boolean {
  const now = input.now ?? new Date();
  if (input.availableFrom && new Date(input.availableFrom) > now) return false;
  if (input.availableUntil && new Date(input.availableUntil) < now) return false;
  return true;
}

export function hasBackendAccess(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }

  if (ADMIN_BYPASS_ROLE_SET.has(input.role)) {
    return true;
  }

  return input.subscriptionEntitlement === 'member_monthly';
}

/**
 * Returns true if the user may contribute to anonymous partner pass-by statistics.
 *
 * Rules (all must be true):
 *  - authenticated user (non-null)
 *  - status is active or warned (not suspended, not deleted)
 *  - anonymousPartnerStatsOptIn is explicitly true
 *
 * Temporarily and permanently suspended users are excluded.
 * Deleted users are excluded.
 * Opt-in defaults to false — backend is authoritative.
 * Client-submitted consent is never trusted.
 */
export function canContributeAnonymousPartnerStats(input: {
  status: UserStatus;
  anonymousPartnerStatsOptIn: boolean;
}): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }
  return input.anonymousPartnerStatsOptIn === true;
}
