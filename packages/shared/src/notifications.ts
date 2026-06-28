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
 *  - TODO: Add production APNs / FCM / Expo push credentials after security review.
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

/**
 * Future categories that are defined in contracts but NOT yet used for delivery.
 * Do not send or activate these without product and security review.
 */
export const FUTURE_NOTIFICATION_CATEGORIES = [
  'partner_offer',
  'event_chat',
  'nearby_event',
] as const;
export type FutureNotificationCategory = (typeof FUTURE_NOTIFICATION_CATEGORIES)[number];

/** All defined category values (active + future). */
export const ALL_NOTIFICATION_CATEGORIES = [
  ...ACTIVE_NOTIFICATION_CATEGORIES,
  ...FUTURE_NOTIFICATION_CATEGORIES,
] as const;
export type AnyNotificationCategory = (typeof ALL_NOTIFICATION_CATEGORIES)[number];

/**
 * Essential account notice categories that may not be fully disabled in-app.
 * Users cannot turn off legally or operationally necessary in-app account notices.
 */
export const ESSENTIAL_NOTIFICATION_CATEGORIES: ReadonlyArray<NotificationCategory> = [
  'account_warning',
  'account_suspension',
] as const;

// ---------------------------------------------------------------------------
// Delivery channels
// ---------------------------------------------------------------------------

export const NOTIFICATION_CHANNELS = ['in_app', 'push'] as const;
export type NotificationChannel = (typeof NOTIFICATION_CHANNELS)[number];

// ---------------------------------------------------------------------------
// Device platforms
// ---------------------------------------------------------------------------

export const NOTIFICATION_PLATFORMS = ['ios', 'android'] as const;
export type NotificationDevicePlatform = (typeof NOTIFICATION_PLATFORMS)[number];

// ---------------------------------------------------------------------------
// Android notification channels
// ---------------------------------------------------------------------------

/**
 * Android notification channel IDs.
 * Prepared at app startup; do not create one channel per notification.
 */
export const ANDROID_NOTIFICATION_CHANNELS = ['events', 'account', 'system'] as const;
export type AndroidNotificationChannelId = (typeof ANDROID_NOTIFICATION_CHANNELS)[number];

/** Map from notification category to Android channel. */
export const NOTIFICATION_CATEGORY_TO_ANDROID_CHANNEL: Record<
  NotificationCategory,
  AndroidNotificationChannelId
> = {
  event_reminder: 'events',
  event_updated: 'events',
  event_cancelled: 'events',
  admin_message: 'system',
  account_warning: 'account',
  account_suspension: 'account',
  subscription_status: 'account',
  system_notice: 'system',
};

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
export type NotificationActionType = (typeof NOTIFICATION_ACTION_TYPES)[number];

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const NOTIFICATION_ROUTE_PATHS = {
  /** GET / POST /v1/notifications */
  list: '/v1/notifications',
  /** POST /v1/notifications/read-all */
  readAll: '/v1/notifications/read-all',
  /** POST /v1/notifications/devices */
  registerDevice: '/v1/notifications/devices',
  /** GET /v1/users/me/notification-preferences */
  preferences: '/v1/users/me/notification-preferences',
  adminList: '/v1/admin/notifications',
} as const;

export function buildNotificationDetailPath(notificationId: string): string {
  return `/v1/notifications/${notificationId}`;
}

export function buildNotificationReadPath(notificationId: string): string {
  return `/v1/notifications/${notificationId}/read`;
}

export function buildNotificationDevicePath(deviceId: string): string {
  return `/v1/notifications/devices/${deviceId}`;
}

export function buildAdminNotificationDetailPath(batchId: string): string {
  return `/v1/admin/notifications/${batchId}`;
}

// ---------------------------------------------------------------------------
// Default limits
// ---------------------------------------------------------------------------

export const DEFAULT_NOTIFICATION_PAGE_SIZE = 20;
export const MAX_NOTIFICATION_PAGE_SIZE = 50;

/**
 * Maximum title length (characters). Keep short for lock-screen safety.
 */
export const MAX_NOTIFICATION_TITLE_LENGTH = 100;

/**
 * Maximum preview text length. Preview text must not contain protected details.
 */
export const MAX_NOTIFICATION_PREVIEW_LENGTH = 200;

/**
 * Maximum body text length (full in-app body).
 */
export const MAX_NOTIFICATION_BODY_LENGTH = 1000;

/**
 * Retention: normal in-app notifications expire after this many days.
 */
export const NOTIFICATION_RETENTION_DAYS = 90;

/**
 * Retention: important account notices may use a longer retention.
 */
export const NOTIFICATION_ACCOUNT_RETENTION_DAYS = 365;

// ---------------------------------------------------------------------------
// Register push device request / response
// ---------------------------------------------------------------------------

/**
 * Request body for registering a push device token.
 *
 * Security requirements:
 *  - User ID must never be accepted from the client; it is derived from the session.
 *  - The raw token must not be returned in any response.
 *  - Token format is validated conservatively by the backend.
 *  - Registration is idempotent (same token = update existing record).
 */
export interface RegisterPushDeviceRequest {
  /** Device platform. */
  platform: NotificationDevicePlatform;
  /**
   * Push token from the device OS / Expo.
   * Backend stores only an encrypted form and a lookup hash.
   * Never log this value.
   */
  pushToken: string;
  /** App semantic version (e.g. "1.0.0"). Optional, for diagnostics. */
  appVersion?: string;
  /** Build number. Optional, for diagnostics. */
  buildNumber?: string;
}

/**
 * Response from a successful device registration.
 * Raw push token is never returned.
 */
export interface RegisterPushDeviceResponse {
  ok: true;
  data: {
    /** Opaque device registration ID. Use this to unregister. */
    deviceId: string;
    platform: NotificationDevicePlatform;
    registeredAt: string;
  };
}

/**
 * Request body for unregistering a push device.
 */
export interface UnregisterPushDeviceRequest {
  // No body required; deviceId is in the path.
}

export interface UnregisterPushDeviceResponse {
  ok: true;
  data: { deactivated: boolean };
}

// ---------------------------------------------------------------------------
// Notification preference
// ---------------------------------------------------------------------------

/**
 * A single user notification preference.
 *
 * Essential account categories may not be fully disabled.
 * Backend enforces category allow-lists.
 */
export interface NotificationPreferenceSummary {
  category: NotificationCategory;
  pushEnabled: boolean;
  inAppEnabled: boolean;
  updatedAt: string;
}

export interface GetNotificationPreferencesResponse {
  ok: true;
  data: {
    preferences: NotificationPreferenceSummary[];
  };
}

/**
 * Request body for updating notification preferences.
 * A partial update; omitted categories are not changed.
 */
export interface PatchNotificationPreferencesRequest {
  preferences: Array<{
    category: NotificationCategory;
    pushEnabled?: boolean;
    inAppEnabled?: boolean;
  }>;
}

export interface PatchNotificationPreferencesResponse {
  ok: true;
  data: {
    preferences: NotificationPreferenceSummary[];
  };
}

// ---------------------------------------------------------------------------
// In-app notification summary (list item)
// ---------------------------------------------------------------------------

/**
 * Safe notification summary for the inbox list.
 *
 * Excluded: raw push token, exact location, session token, provider identity,
 *   private chat text, discount code, raw moderation metadata, arbitrary URLs.
 */
export interface NotificationSummary {
  /** Opaque notification identifier. */
  notificationId: string;
  category: NotificationCategory;
  /** Swedish title. */
  title: string;
  /** Swedish preview text. Must not contain protected details. */
  previewText: string;
  createdAt: string;
  readAt: string | null;
  actionType: NotificationActionType;
  /** Opaque related entity ID (e.g. eventId). Optional. */
  relatedEntityId: string | null;
}

// ---------------------------------------------------------------------------
// In-app notification detail (single notification)
// ---------------------------------------------------------------------------

/**
 * Full notification detail.
 * Body may contain more text than the preview, but must still be plain text.
 */
export interface NotificationDetail extends NotificationSummary {
  body: string | null;
  expiresAt: string | null;
}

// ---------------------------------------------------------------------------
// Paginated notification list response
// ---------------------------------------------------------------------------

export interface PaginatedNotificationsResponse {
  ok: true;
  data: {
    notifications: NotificationSummary[];
    unreadCount: number;
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

// ---------------------------------------------------------------------------
// Single notification response
// ---------------------------------------------------------------------------

export interface NotificationDetailResponse {
  ok: true;
  data: NotificationDetail;
}

// ---------------------------------------------------------------------------
// Mark read response
// ---------------------------------------------------------------------------

export interface MarkNotificationReadResponse {
  ok: true;
  data: { notificationId: string; readAt: string };
}

export interface MarkAllNotificationsReadResponse {
  ok: true;
  data: { markedCount: number };
}

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

// ---------------------------------------------------------------------------
// Admin notification summary (list item)
// ---------------------------------------------------------------------------

export interface AdminNotificationBatchSummary {
  batchId: string;
  category: NotificationCategory;
  audience: AdminNotificationAudience;
  title: string;
  recipientCount: number;
  reason: string;
  createdAt: string;
  createdByUserId: string;
}

export interface PaginatedAdminNotificationBatchesResponse {
  ok: true;
  data: {
    batches: AdminNotificationBatchSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface AdminNotificationBatchDetailResponse {
  ok: true;
  data: AdminNotificationBatchSummary;
}

// ---------------------------------------------------------------------------
// Notification delivery result (internal — not exposed in API responses)
// ---------------------------------------------------------------------------

export type NotificationDeliveryStatus = 'pending' | 'sent' | 'delivered' | 'failed' | 'skipped';

/**
 * Internal delivery result record.
 * Do not expose provider message IDs or raw provider responses in API responses.
 */
export interface NotificationDeliveryResult {
  channel: NotificationChannel;
  status: NotificationDeliveryStatus;
  /** Safe, non-sensitive error code for diagnostics. Never include token values. */
  safeErrorCode?: string;
}
