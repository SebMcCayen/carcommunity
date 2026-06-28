/**
 * Notifications API client for the mobile app.
 *
 * Privacy rules:
 *  - Push tokens are never logged or exposed in error messages.
 *  - User ID is always derived from the authenticated session on the backend.
 *  - Only the current user's notifications and preferences are fetched.
 *  - Protected notification details are loaded from the backend after app open.
 *
 * Security notes:
 *  - Push tokens are sent only to the backend; never stored in plain state.
 *  - Device registration is idempotent and safe to retry.
 *  - Logout should call unregisterDevice to deactivate the token.
 *
 * TODO (production):
 *  - Add Expo push token acquisition via expo-notifications.
 *  - Add iOS capabilities (push notifications entitlement).
 *  - Add Android notification channels at app startup.
 *  - Add APNs/FCM or Expo push credentials to Azure Key Vault.
 *  - Test on physical devices before production deployment.
 */

import {
  NOTIFICATION_ROUTE_PATHS,
  buildNotificationDetailPath,
  buildNotificationReadPath,
  buildNotificationDevicePath,
  DEFAULT_NOTIFICATION_PAGE_SIZE,
  type PaginatedNotificationsResponse,
  type NotificationDetailResponse,
  type MarkNotificationReadResponse,
  type MarkAllNotificationsReadResponse,
  type RegisterPushDeviceResponse,
  type UnregisterPushDeviceResponse,
  type GetNotificationPreferencesResponse,
  type PatchNotificationPreferencesResponse,
  type NotificationCategory,
} from '@carcommunity/shared/notifications';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

export class NotificationsApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'NotificationsApiError';
  }
}

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new NotificationsApiError(
      response.status,
      `Notifications request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

// ---------------------------------------------------------------------------
// In-app notifications
// ---------------------------------------------------------------------------

/**
 * Fetch the current user's paginated notification inbox.
 * Returns newest first. Excludes expired notifications.
 */
export async function listNotifications(
  token?: string,
  page = 1,
  pageSize = DEFAULT_NOTIFICATION_PAGE_SIZE,
): Promise<PaginatedNotificationsResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  return requestJson<PaginatedNotificationsResponse>(
    `${NOTIFICATION_ROUTE_PATHS.list}?${params.toString()}`,
    { method: 'GET', headers: buildAuthHeader(token) },
  );
}

/**
 * Fetch a single notification detail.
 * Protected details must be re-validated from the backend after app opens.
 */
export async function getNotificationDetail(
  notificationId: string,
  token?: string,
): Promise<NotificationDetailResponse> {
  return requestJson<NotificationDetailResponse>(buildNotificationDetailPath(notificationId), {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
}

/**
 * Mark a single notification as read. Idempotent.
 */
export async function markNotificationRead(
  notificationId: string,
  token?: string,
): Promise<MarkNotificationReadResponse> {
  return requestJson<MarkNotificationReadResponse>(buildNotificationReadPath(notificationId), {
    method: 'POST',
    headers: { ...buildAuthHeader(token), 'Content-Type': 'application/json' },
  });
}

/**
 * Mark all notifications as read.
 */
export async function markAllNotificationsRead(
  token?: string,
): Promise<MarkAllNotificationsReadResponse> {
  return requestJson<MarkAllNotificationsReadResponse>(NOTIFICATION_ROUTE_PATHS.readAll, {
    method: 'POST',
    headers: { ...buildAuthHeader(token), 'Content-Type': 'application/json' },
  });
}

// ---------------------------------------------------------------------------
// Push device registration
// ---------------------------------------------------------------------------

/**
 * Register or refresh a push device token.
 *
 * Security notes:
 *  - Never log the pushToken value.
 *  - Only call this after the user has granted push permission.
 *  - The raw token is never returned in the response.
 *  - Registration is idempotent.
 *
 * TODO: Acquire the pushToken via expo-notifications after production setup.
 */
export async function registerPushDevice(
  input: {
    platform: 'ios' | 'android';
    pushToken: string;
    appVersion?: string;
    buildNumber?: string;
  },
  token?: string,
): Promise<RegisterPushDeviceResponse> {
  return requestJson<RegisterPushDeviceResponse>(NOTIFICATION_ROUTE_PATHS.registerDevice, {
    method: 'POST',
    headers: { ...buildAuthHeader(token), 'Content-Type': 'application/json' },
    // Never log this body — it contains a push token.
    body: JSON.stringify(input),
  });
}

/**
 * Unregister a push device (e.g. on logout).
 * Call this when the user logs out so stale tokens are not delivered to.
 */
export async function unregisterPushDevice(
  deviceId: string,
  token?: string,
): Promise<UnregisterPushDeviceResponse> {
  return requestJson<UnregisterPushDeviceResponse>(buildNotificationDevicePath(deviceId), {
    method: 'DELETE',
    headers: buildAuthHeader(token),
  });
}

// ---------------------------------------------------------------------------
// Preferences
// ---------------------------------------------------------------------------

/**
 * Fetch the current user's notification preferences.
 */
export async function getNotificationPreferences(
  token?: string,
): Promise<GetNotificationPreferencesResponse> {
  return requestJson<GetNotificationPreferencesResponse>(NOTIFICATION_ROUTE_PATHS.preferences, {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
}

/**
 * Update notification preferences for specific categories.
 */
export async function patchNotificationPreferences(
  updates: Array<{ category: NotificationCategory; pushEnabled?: boolean; inAppEnabled?: boolean }>,
  token?: string,
): Promise<PatchNotificationPreferencesResponse> {
  return requestJson<PatchNotificationPreferencesResponse>(NOTIFICATION_ROUTE_PATHS.preferences, {
    method: 'PATCH',
    headers: { ...buildAuthHeader(token), 'Content-Type': 'application/json' },
    body: JSON.stringify({ preferences: updates }),
  });
}
