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
 * The FULL set of delivered notification categories — every category the backend
 * can write to a member's inbox, mirroring the backend NOTIFICATION_CATEGORIES.
 * This INCLUDES the producer-only categories an admin cannot broadcast: the
 * social ones (member-to-member activity) AND `event_created` (a system-generated
 * community broadcast the backend fires on event publish). `NotificationCategory`
 * derives from this set, so the type can represent EVERY delivered category
 * (e.g. `event_created`) — matching the backend and the app's own settings
 * (Android NotificationCategories). The admin-sendable subset that drives the
 * "send notification" dropdown is ADMIN_SENDABLE_CATEGORIES below.
 */
export const NOTIFICATION_CATEGORIES = [
  'event_created',
  'event_reminder',
  'event_updated',
  'event_cancelled',
  'admin_message',
  'account_warning',
  'account_suspension',
  'subscription_status',
  'system_notice',
  'direct_message',
  'community_chat',
  'convoy_chat',
  'friend_request',
  'convoy_invite',
  'convoy_update',
] as const;
export type NotificationCategory = (typeof NOTIFICATION_CATEGORIES)[number];

/**
 * The ADMIN-SENDABLE subset — the categories an admin may broadcast, and the
 * only ones the admin "send notification" dropdown offers (mirrors the backend
 * ADMIN_SENDABLE_CATEGORIES). The producer-only categories above — the social
 * ones AND `event_created` — are deliberately absent so the dropdown never
 * offers a category `notifications.adminSend` would reject. Per-category opt-outs
 * for the absent categories are still delivered/honoured backend-side and exposed
 * in the app's own settings. Distinct from NOTIFICATION_CATEGORIES on purpose:
 * this is "what an admin may send", not "what may be delivered".
 */
export const ADMIN_SENDABLE_CATEGORIES = [
  'event_reminder',
  'event_updated',
  'event_cancelled',
  'admin_message',
  'account_warning',
  'account_suspension',
  'subscription_status',
  'system_notice',
] as const;
export type AdminSendableCategory = (typeof ADMIN_SENDABLE_CATEGORIES)[number];

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
 *  - Category must be from the admin-sendable allow-list.
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
  category: AdminSendableCategory;
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

