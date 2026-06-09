import type {
  SubscriptionEntitlement,
  UserRole,
  UserStatus,
} from '@carcommunity/shared/users';

/**
 * Shared admin portal type definitions.
 *
 * These are local placeholder types. Real types should be derived from the
 * backend API schema once API integration is implemented.
 *
 * TODO: Replace with generated types from the backend API schema once available.
 */

export type { UserRole, UserStatus, SubscriptionEntitlement };
export type ModerationStatus = Extract<UserStatus, 'warned' | 'temporarily_suspended' | 'permanently_suspended'>;

export type ReportStatus = 'open' | 'under_review' | 'resolved' | 'dismissed';

export type ReportReason =
  | 'inappropriate_content'
  | 'harassment'
  | 'spam'
  | 'safety_concern'
  | 'other';

export type PartnerStatus = 'pending' | 'active' | 'suspended';

export type BillboardStatus = 'pending_review' | 'active' | 'paused' | 'rejected';

export type AuditAction =
  | 'user.suspend'
  | 'user.unsuspend'
  | 'user.delete'
  | 'report.resolve'
  | 'report.dismiss'
  | 'partner.approve'
  | 'partner.suspend'
  | 'billboard.approve'
  | 'billboard.reject'
  | 'feature_flag.update';

/**
 * Placeholder admin user type.
 * TODO: Replace with real admin identity from Microsoft Entra ID after
 * authentication is implemented. Admin role must be verified by backend.
 */
export interface AdminUser {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
}
