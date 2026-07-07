/**
 * Notifications feature module for the admin portal
 * (Phase 13i — Firebase migration).
 *
 * Migrated from the legacy `apiRequest` REST client to the Firebase callable
 * client (`callAdmin`). The admin batch send is backed by:
 *  - notifications-adminSend → fan an in-app notification out to an audience
 *    (idempotent per idempotencyKey; all_users/free_users require confirmed).
 *
 * Design decision (documented): the legacy send-history browser
 * (adminListNotifications / adminGetNotificationBatch) is intentionally NOT
 * carried over. Batch records live in `adminNotificationBatches`, which is
 * backend-only (firestore.rules never grant a client read — it is idempotency +
 * fan-out infrastructure, not an admin-facing collection), and the migrated
 * backend exposes no admin batch-list callable. A history view would need a new
 * backend read path (a batches read grant or an adminAuditEvents action index),
 * which is out of scope for this admin migration. The page is send-only.
 *
 * Security notes:
 *  - Backend validates all recipient eligibility and access control.
 *  - Push tokens are never returned in any response.
 *  - Body and title are plain text only — no HTML.
 *  - Reason is mandatory and written to the audit log by the backend.
 *  - Idempotency key prevents duplicate sends.
 *  - all_users and free_users audiences require confirmed=true.
 */

import {
  ACTIVE_NOTIFICATION_CATEGORIES,
  ADMIN_NOTIFICATION_AUDIENCES,
  NOTIFICATION_ACTION_TYPES,
  type AdminNotificationAudience,
  type AdminSendNotificationRequest,
  type AdminSendNotificationResponse,
  type NotificationCategory,
} from '@carcommunity/shared/notifications';

import { ApiError } from '../../lib/api';
import { callAdmin } from '../../lib/callables';

export type {
  NotificationCategory,
  AdminNotificationAudience,
  AdminSendNotificationRequest,
  AdminSendNotificationResponse,
};
export { ApiError, ACTIVE_NOTIFICATION_CATEGORIES, ADMIN_NOTIFICATION_AUDIENCES, NOTIFICATION_ACTION_TYPES };

// ---------------------------------------------------------------------------
// Callable-backed data layer
// ---------------------------------------------------------------------------

/**
 * Sends a notification to a bounded audience via `notifications-adminSend`.
 * Requires admin or owner (verified server-side). Reason + idempotencyKey are
 * mandatory; all_users/free_users require `confirmed: true`.
 *
 * The callable's strict schema rejects unknown keys, so only defined request
 * fields are forwarded. The raw `{ batchId, audience, recipientCount, createdAt }`
 * result is wrapped in the REST envelope the page consumes.
 */
export async function adminSendNotification(
  request: AdminSendNotificationRequest,
): Promise<AdminSendNotificationResponse> {
  // Drop undefined optionals so the backend's strict schema never sees them.
  const payload = Object.fromEntries(
    Object.entries(request).filter(([, value]) => value !== undefined),
  );

  const result = await callAdmin<{
    batchId: string;
    audience: AdminNotificationAudience;
    recipientCount: number;
    createdAt: string;
  }>('notifications-adminSend', payload);

  return { ok: true, data: result };
}
