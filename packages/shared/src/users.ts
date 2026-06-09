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

export interface AuditLogSummary {
  id: string;
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  reason: string | null;
  createdAt: string;
}

export interface CurrentUserResponse {
  ok: true;
  data: {
    user: UserSummary;
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
