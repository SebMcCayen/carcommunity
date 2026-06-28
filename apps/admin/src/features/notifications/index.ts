/**
 * Notifications feature module for the admin portal.
 *
 * Provides API client functions for managing notifications and viewing send history.
 *
 * Security notes:
 *  - Backend validates all recipient eligibility and access control.
 *  - Push tokens are never returned in any response.
 *  - Body and title are plain text only — no HTML.
 *  - Reason is mandatory and written to the audit log.
 *  - Idempotency key prevents duplicate sends.
 *  - all_users and free_users audiences require confirmed=true.
 *  - Audit log records are written for every admin send.
 */

import {
  NOTIFICATION_ROUTE_PATHS,
  buildAdminNotificationDetailPath,
  ACTIVE_NOTIFICATION_CATEGORIES,
  ADMIN_NOTIFICATION_AUDIENCES,
  NOTIFICATION_ACTION_TYPES,
  type AdminSendNotificationResponse,
  type PaginatedAdminNotificationBatchesResponse,
  type AdminNotificationBatchDetailResponse,
  type AdminSendNotificationRequest,
  type NotificationCategory,
  type AdminNotificationAudience,
  type AdminNotificationBatchSummary,
} from '@carcommunity/shared/notifications';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  NotificationCategory,
  AdminNotificationAudience,
  AdminSendNotificationRequest,
  AdminNotificationBatchSummary,
  PaginatedAdminNotificationBatchesResponse,
  AdminNotificationBatchDetailResponse,
};
export { ApiError, ACTIVE_NOTIFICATION_CATEGORIES, ADMIN_NOTIFICATION_AUDIENCES, NOTIFICATION_ACTION_TYPES };

// ---------------------------------------------------------------------------
// API functions
// ---------------------------------------------------------------------------

/**
 * Send a notification to a bounded audience.
 * Requires admin or owner. Reason is mandatory. Idempotency key required.
 * all_users and free_users require confirmed=true.
 */
export async function adminSendNotification(
  request: AdminSendNotificationRequest,
  token?: string,
): Promise<AdminSendNotificationResponse> {
  return apiRequest<AdminSendNotificationResponse>(NOTIFICATION_ROUTE_PATHS.adminList, {
    method: 'POST',
    body: request,
    token,
  });
}

/**
 * List admin notification batches (send history), newest first.
 */
export async function adminListNotifications(
  page = 1,
  pageSize = 20,
  token?: string,
): Promise<PaginatedAdminNotificationBatchesResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  return apiRequest<PaginatedAdminNotificationBatchesResponse>(
    `${NOTIFICATION_ROUTE_PATHS.adminList}?${params.toString()}`,
    { method: 'GET', token },
  );
}

/**
 * Get a specific admin notification batch by ID.
 */
export async function adminGetNotificationBatch(
  batchId: string,
  token?: string,
): Promise<AdminNotificationBatchDetailResponse> {
  return apiRequest<AdminNotificationBatchDetailResponse>(
    buildAdminNotificationDetailPath(batchId),
    { method: 'GET', token },
  );
}
