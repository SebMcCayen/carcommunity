/**
 * Shared contracts for the notification foundation.
 *
 * Design rules encoded here:
 *  - Push notifications are opt-in. Denying permission must not prevent normal app use.
 *  - Users may disable notification categories.
 *  - Notification payloads contain minimal data — no protected details.
 *  - Protected content is loaded from the backend after the app opens.
 *  - Deleted users must not receive normal app notifications.
 *  - Suspended users may receive essential account and support-related notices.
 *  - Backend is the sole authority for notification eligibility and delivery state.
 *  - Client-side preferences are not a security boundary.
 *
 * Excluded from these contracts:
 *  - Raw push tokens
 *  - Exact live location data
 *  - Private chat content
 *  - Discount codes
 *  - Moderation details on the lock screen
 *  - Sensitive account information
 *  - Arbitrary external URLs
 *  - Internal delivery metadata (provider message IDs, raw provider responses)
 *
 * Future preparation (not activated in MVP):
 *  - TODO: Activate `partner_offer` category once partner notification rules are approved.
 *  - TODO: Activate `event_chat` category once chat notification design is finalised.
 *  - TODO: Activate `nearby_event` category when proximity feature is designed.
 *  - TODO: Add production APNs / FCM push credentials after security review.
 *  - TODO: Add background worker / queue for large audience fan-out.
 *  - TODO: Add Azure scheduling for event reminders.
 */

// ---------------------------------------------------------------------------
// Notification categories
// ---------------------------------------------------------------------------

/**
 * Active notification categories supported in this MVP.
 * These may be sent to users and appear in preference settings.
 */
export const ACTIVE_NOTIFICATION_CATEGORIES = [
  'event_created',
  'event_reminder',
  'event_updated',
  'event_cancelled',
  'admin_message',
  'account_warning',
  'account_suspension',
  'subscription_status',
  'system_notice',
] as const;
export type NotificationCategory = (typeof ACTIVE_NOTIFICATION_CATEGORIES)[number];

// ---------------------------------------------------------------------------
// Admin audience types
// ---------------------------------------------------------------------------

export const ADMIN_NOTIFICATION_AUDIENCES = [
  'all_users',
  'free_users',
  'members',
  'event_participants',
  'specific_user',
  'admins',
] as const;
export type AdminNotificationAudience = (typeof ADMIN_NOTIFICATION_AUDIENCES)[number];

// ---------------------------------------------------------------------------
// Notification action types (for deep-link routing in the mobile app)
// ---------------------------------------------------------------------------

/**
 * Safe action types for deep-linking after a notification tap.
 * Only allowlisted internal destinations are permitted.
 * Protected data must be re-validated by the backend after the app opens.
 */
export const NOTIFICATION_ACTION_TYPES = [
  'open_notifications',
  'open_event',
  'open_profile',
  'open_subscription',
  'open_settings',
  'none',
] as const;
type NotificationActionType = (typeof NOTIFICATION_ACTION_TYPES)[number];

// ---------------------------------------------------------------------------
// Admin notification request
// ---------------------------------------------------------------------------

/**
 * Request body for an admin-initiated notification send.
 *
 * Security requirements:
 *  - Category must be from the active allow-list.
 *  - Audience must be from the defined audience types.
 *  - eventId is required for event_participants audience.
 *  - userId is required for specific_user audience.
 *  - Reason is mandatory and is written to the audit log.
 *  - No arbitrary HTML; body is plain text only.
 *  - No arbitrary external URLs.
 *  - All-user sends require explicit confirmation.
 *  - Idempotency key prevents duplicate sends.
 *  - Backend validates recipient eligibility independently.
 */
export interface AdminSendNotificationRequest {
  category: NotificationCategory;
  audience: AdminNotificationAudience;
  title: string;
  previewText: string;
  body: string;
  actionType?: NotificationActionType;
  /** Required when audience is event_participants. */
  eventId?: string;
  /** Required when audience is specific_user. */
  targetUserId?: string;
  /** Mandatory reason written to the audit log. */
  reason: string;
  /**
   * Idempotency key to prevent duplicate sends.
   * Must be unique per intended send operation.
   */
  idempotencyKey: string;
  /**
   * Explicit confirmation required for all_users and free_users audiences.
   * Must be true for those audiences to proceed.
   */
  confirmed?: boolean;
}

// ---------------------------------------------------------------------------
// Admin notification send result
// ---------------------------------------------------------------------------

export interface AdminSendNotificationResponse {
  ok: true;
  data: {
    /** Batch ID for this admin send operation. */
    batchId: string;
    audience: AdminNotificationAudience;
    /** Number of recipients targeted. */
    recipientCount: number;
    createdAt: string;
  };
}

