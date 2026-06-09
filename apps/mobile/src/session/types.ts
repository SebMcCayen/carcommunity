import type {
  SubscriptionEntitlement,
  UserRole,
  UserStatus,
} from '@carcommunity/shared/users';

export interface MobileSessionUser {
  id: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface MobileSessionState {
  currentUser: MobileSessionUser | null;
}
