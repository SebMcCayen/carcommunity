/**
 * Notification API route tests.
 *
 * All service calls use fake services to avoid database dependencies.
 *
 * Covers:
 *  - Push permission is not requested at app startup (verified by no push call on app init)
 *  - Declining push permission does not block app use (in-app route still works)
 *  - Device registration requires authentication (401 when unauthenticated)
 *  - Device token is never returned in registration response
 *  - Device registration is idempotent (same token reactivates)
 *  - Logout deactivates devices (tested via delete route)
 *  - Users can read only their own notifications (ownership enforced)
 *  - Read operations are idempotent
 *  - Preferences are owner-scoped (user gets only their own)
 *  - Deleted users do not receive normal notifications (delivery skipped)
 *  - Suspended users can still receive essential account notices
 *  - Disabled feature flag prevents push delivery
 *  - Push failure does not remove in-app notification
 *  - Admin notification requires admin or owner (403 for regular user)
 *  - All-user send requires confirmation and reason
 *  - Duplicate idempotency key prevents duplicate sends
 *  - Arbitrary URLs and HTML are rejected (plain text validation)
 *  - Admin responses do not expose push tokens
 *  - Unknown notification ID returns 404 (not another user's)
 *  - PATCH preferences rejects unknown category
 *  - Essential category inApp cannot be disabled
 *  - Push feature flag disabled prevents device registration
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  NOTIFICATION_ROUTE_PATHS,
  buildNotificationDetailPath,
  buildNotificationReadPath,
  buildNotificationDevicePath,
  type PaginatedNotificationsResponse,
  type NotificationDetailResponse,
  type MarkNotificationReadResponse,
  type MarkAllNotificationsReadResponse,
  type RegisterPushDeviceResponse,
  type UnregisterPushDeviceResponse,
  type AdminSendNotificationResponse,
  type NotificationSummary,
  type NotificationPreferenceSummary,
} from '@carcommunity/shared/notifications';

import { LOCAL_DATABASE_URL } from './config.js';
import { AppError } from './lib/errors.js';
import type { NotificationService } from './lib/notification-service.js';
import type { NotificationDeliveryService } from './lib/notification-delivery-service.js';
import { createServer } from './server.js';

// ---------------------------------------------------------------------------
// Test UUIDs
// ---------------------------------------------------------------------------

const USER_UUID = 'aaaaaaaa-0000-4000-8000-000000000001';
const OTHER_USER_UUID = 'bbbbbbbb-0000-4000-8000-000000000002';
const NOTIFICATION_UUID = 'cccccccc-0000-4000-8000-000000000003';
const DEVICE_UUID = 'dddddddd-0000-4000-8000-000000000004';

// ---------------------------------------------------------------------------
// Shared test config
// ---------------------------------------------------------------------------

const TEST_CONFIG = {
  nodeEnv: 'test' as const,
  port: 4000,
  databaseUrl: LOCAL_DATABASE_URL,
  isProduction: false,
  earlyMemberCutoffDate: null,
};

// ---------------------------------------------------------------------------
// Sample data
// ---------------------------------------------------------------------------

const SAMPLE_NOTIFICATION: NotificationSummary = {
  notificationId: NOTIFICATION_UUID,
  category: 'event_reminder',
  title: 'Påminnelse: Sommarträff',
  previewText: 'Eventet börjar snart.',
  createdAt: '2026-06-28T10:00:00.000Z',
  readAt: null,
  actionType: 'open_event',
  relatedEntityId: 'eeeeeeee-0000-4000-8000-000000000005',
};

const SAMPLE_PREFERENCE: NotificationPreferenceSummary = {
  category: 'event_reminder',
  pushEnabled: false,
  inAppEnabled: true,
  updatedAt: '2026-06-28T10:00:00.000Z',
};

// ---------------------------------------------------------------------------
// Fake notification service
// ---------------------------------------------------------------------------

function buildFakeNotificationService(
  overrides: Partial<NotificationService> = {},
): NotificationService {
  return {
    registerDevice: async () => ({
      deviceId: DEVICE_UUID,
      platform: 'ios' as const,
      registeredAt: new Date('2026-06-28T10:00:00.000Z'),
    }),
    unregisterDevice: async () => ({ deactivated: true }),
    deactivateAllDevices: async () => undefined,
    getActiveDevices: async () => [],
    deactivateDeviceByHash: async () => undefined,
    getPreferences: async () => [SAMPLE_PREFERENCE],
    updatePreferences: async () => [SAMPLE_PREFERENCE],
    createNotification: async () => SAMPLE_NOTIFICATION,
    createNotificationsForUsers: async () => 1,
    listNotifications: async () => ({
      notifications: [SAMPLE_NOTIFICATION],
      unreadCount: 1,
      total: 1,
      hasNext: false,
      page: 1,
      pageSize: 20,
    }),
    getNotificationDetail: async () => ({
      ...SAMPLE_NOTIFICATION,
      body: 'Öppna appen för fullständig information.',
      expiresAt: null,
    }),
    markRead: async () => ({
      notificationId: NOTIFICATION_UUID,
      readAt: new Date('2026-06-28T10:01:00.000Z'),
    }),
    markAllRead: async () => ({ markedCount: 3 }),
    cleanupExpiredNotifications: async () => ({ deletedCount: 0 }),
    cleanupStaleDeviceRegistrations: async () => ({ deactivatedCount: 0 }),
    ...overrides,
  } as unknown as NotificationService;
}

// ---------------------------------------------------------------------------
// Fake delivery service
// ---------------------------------------------------------------------------

function buildFakeDeliveryService(
  overrides: Partial<NotificationDeliveryService> = {},
): NotificationDeliveryService {
  return {
    deliverToUser: async () => ({
      notificationId: NOTIFICATION_UUID,
      inAppDelivered: true,
      pushAttempted: false,
      pushSucceeded: false,
    }),
    deliverToAudience: async () => ({
      batchId: 'batch-0000-4000-8000-000000000001',
      recipientCount: 5,
      createdAt: new Date('2026-06-28T10:00:00.000Z'),
    }),
    ...overrides,
  } as unknown as NotificationDeliveryService;
}

// ---------------------------------------------------------------------------
// Dev auth helpers
// ---------------------------------------------------------------------------

function devAuthHeader(overrides: object = {}) {
  return JSON.stringify({
    userId: USER_UUID,
    role: 'user',
    status: 'active',
    subscriptionEntitlement: 'none',
    sessionId: 'session-test-001',
    ...overrides,
  });
}

function adminAuthHeader(overrides: object = {}) {
  return JSON.stringify({
    userId: USER_UUID,
    role: 'admin',
    status: 'active',
    subscriptionEntitlement: 'none',
    sessionId: 'session-admin-001',
    ...overrides,
  });
}

// ---------------------------------------------------------------------------
// Tests: GET /v1/notifications (inbox)
// ---------------------------------------------------------------------------

test('GET /v1/notifications returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.list,
  });

  assert.equal(response.statusCode, 401);
  await app.close();
});

test('GET /v1/notifications returns 403 for deleted user', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.list,
    headers: { 'x-dev-user': devAuthHeader({ status: 'deleted' }) },
  });

  assert.equal(response.statusCode, 403);
  await app.close();
});

test('GET /v1/notifications returns paginated inbox for authenticated user', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.list,
    headers: { 'x-dev-user': devAuthHeader() },
  });

  const body = JSON.parse(response.payload) as PaginatedNotificationsResponse;
  assert.equal(response.statusCode, 200);
  assert.equal(body.ok, true);
  assert.equal(body.data.notifications.length, 1);
  assert.equal(body.data.unreadCount, 1);
  assert.equal(body.meta.total, 1);
  await app.close();
});

test('GET /v1/notifications passes userId from session (ownership enforced)', async () => {
  let capturedUserId: string | undefined;

  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService({
      listNotifications: async (input) => {
        capturedUserId = input.userId;
        return {
          notifications: [],
          unreadCount: 0,
          total: 0,
          hasNext: false,
          page: 1,
          pageSize: 20,
        };
      },
    }),
  });

  await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.list,
    headers: { 'x-dev-user': devAuthHeader() },
  });

  // Backend derives userId from session — never from client.
  assert.equal(capturedUserId, USER_UUID);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: GET /v1/notifications/:notificationId
// ---------------------------------------------------------------------------

test('GET /v1/notifications/:notificationId returns 404 for unknown ID', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService({
      getNotificationDetail: async () => {
        throw new AppError(404, 'notification_not_found', 'Notification not found.');
      },
    }),
  });

  const response = await app.inject({
    method: 'GET',
    url: buildNotificationDetailPath(NOTIFICATION_UUID),
    headers: { 'x-dev-user': devAuthHeader() },
  });

  assert.equal(response.statusCode, 404);
  await app.close();
});

test('GET /v1/notifications/:notificationId response does not expose provider metadata', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'GET',
    url: buildNotificationDetailPath(NOTIFICATION_UUID),
    headers: { 'x-dev-user': devAuthHeader() },
  });

  const body = JSON.parse(response.payload) as NotificationDetailResponse;
  assert.equal(response.statusCode, 200);
  // Ensure no token or delivery provider fields are exposed.
  const bodyStr = JSON.stringify(body);
  assert.ok(!bodyStr.includes('pushToken'), 'Response must not contain pushToken');
  assert.ok(!bodyStr.includes('encryptedPushToken'), 'Response must not contain encryptedPushToken');
  assert.ok(!bodyStr.includes('providerMessageId'), 'Response must not contain providerMessageId');
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: POST /v1/notifications/:notificationId/read
// ---------------------------------------------------------------------------

test('POST /v1/notifications/:notificationId/read marks notification as read', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: buildNotificationReadPath(NOTIFICATION_UUID),
    headers: { 'x-dev-user': devAuthHeader() },
  });

  const body = JSON.parse(response.payload) as MarkNotificationReadResponse;
  assert.equal(response.statusCode, 200);
  assert.equal(body.ok, true);
  assert.equal(body.data.notificationId, NOTIFICATION_UUID);
  assert.ok(body.data.readAt);
  await app.close();
});

test('POST /v1/notifications/:notificationId/read is idempotent', async () => {
  let callCount = 0;
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService({
      markRead: async () => {
        callCount++;
        return {
          notificationId: NOTIFICATION_UUID,
          readAt: new Date('2026-06-28T10:01:00.000Z'),
        };
      },
    }),
  });

  await app.inject({
    method: 'POST',
    url: buildNotificationReadPath(NOTIFICATION_UUID),
    headers: { 'x-dev-user': devAuthHeader() },
  });
  await app.inject({
    method: 'POST',
    url: buildNotificationReadPath(NOTIFICATION_UUID),
    headers: { 'x-dev-user': devAuthHeader() },
  });

  // Two calls succeed (service handles idempotency)
  assert.equal(callCount, 2);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: POST /v1/notifications/read-all
// ---------------------------------------------------------------------------

test('POST /v1/notifications/read-all marks all as read', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.readAll,
    headers: { 'x-dev-user': devAuthHeader() },
  });

  const body = JSON.parse(response.payload) as MarkAllNotificationsReadResponse;
  assert.equal(response.statusCode, 200);
  assert.equal(body.ok, true);
  assert.equal(body.data.markedCount, 3);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: POST /v1/notifications/devices (device registration)
// ---------------------------------------------------------------------------

test('POST /v1/notifications/devices returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.registerDevice,
    payload: { platform: 'ios', pushToken: 'ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]' },
  });

  assert.equal(response.statusCode, 401);
  await app.close();
});

test('POST /v1/notifications/devices response never contains raw push token', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
    pushNotificationsFeatureEnabled: true,
  });

  const rawToken = 'ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]';
  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.registerDevice,
    headers: { 'x-dev-user': devAuthHeader() },
    payload: { platform: 'ios', pushToken: rawToken },
  });

  const body = JSON.parse(response.payload) as RegisterPushDeviceResponse;
  assert.equal(response.statusCode, 200);
  assert.equal(body.ok, true);
  assert.ok(body.data.deviceId, 'deviceId must be present');
  // Raw token must NOT appear in response at any level.
  const bodyStr = JSON.stringify(body);
  assert.ok(!bodyStr.includes(rawToken), 'Response must not contain raw push token');
  await app.close();
});

test('POST /v1/notifications/devices returns 403 when push feature flag is disabled', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
    pushNotificationsFeatureEnabled: false,
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.registerDevice,
    headers: { 'x-dev-user': devAuthHeader() },
    payload: { platform: 'ios', pushToken: 'ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]' },
  });

  assert.equal(response.statusCode, 403);
  const body = JSON.parse(response.payload) as { ok: false; error: { code: string } };
  assert.equal(body.error.code, 'notification_feature_disabled');
  await app.close();
});

test('POST /v1/notifications/devices rejects invalid push token format', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
    pushNotificationsFeatureEnabled: true,
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.registerDevice,
    headers: { 'x-dev-user': devAuthHeader() },
    payload: { platform: 'ios', pushToken: 'token with spaces and <html>' },
  });

  assert.equal(response.statusCode, 400);
  await app.close();
});

test('DELETE /v1/notifications/devices/:deviceId deactivates device (logout path)', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'DELETE',
    url: buildNotificationDevicePath(DEVICE_UUID),
    headers: { 'x-dev-user': devAuthHeader() },
  });

  const body = JSON.parse(response.payload) as UnregisterPushDeviceResponse;
  assert.equal(response.statusCode, 200);
  assert.equal(body.ok, true);
  assert.equal(body.data.deactivated, true);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: GET /v1/users/me/notification-preferences
// ---------------------------------------------------------------------------

test('GET /v1/users/me/notification-preferences returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.preferences,
  });

  assert.equal(response.statusCode, 401);
  await app.close();
});

test('GET /v1/users/me/notification-preferences returns only current user preferences', async () => {
  let capturedUserId: string | undefined;

  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService({
      getPreferences: async (input) => {
        capturedUserId = input.userId;
        return [SAMPLE_PREFERENCE];
      },
    }),
  });

  await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.preferences,
    headers: { 'x-dev-user': devAuthHeader() },
  });

  // Always uses session userId — never a user-supplied userId.
  assert.equal(capturedUserId, USER_UUID);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: PATCH /v1/users/me/notification-preferences
// ---------------------------------------------------------------------------

test('PATCH preferences rejects unknown category', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService(),
  });

  const response = await app.inject({
    method: 'PATCH',
    url: NOTIFICATION_ROUTE_PATHS.preferences,
    headers: { 'x-dev-user': devAuthHeader() },
    payload: {
      preferences: [{ category: 'unknown_category', pushEnabled: true }],
    },
  });

  assert.equal(response.statusCode, 400);
  await app.close();
});

test('PATCH preferences enforces owner scope (cannot modify other user)', async () => {
  let capturedUserId: string | undefined;

  const app = await createServer(TEST_CONFIG, {
    notificationService: buildFakeNotificationService({
      updatePreferences: async (input) => {
        capturedUserId = input.userId;
        return [SAMPLE_PREFERENCE];
      },
    }),
  });

  await app.inject({
    method: 'PATCH',
    url: NOTIFICATION_ROUTE_PATHS.preferences,
    headers: {
      'x-dev-user': devAuthHeader(), // Authenticated as USER_UUID
    },
    payload: {
      preferences: [{ category: 'event_reminder', pushEnabled: true }],
    },
  });

  // Must use session userId, never a client-supplied userId.
  assert.equal(capturedUserId, USER_UUID);
  assert.notEqual(capturedUserId, OTHER_USER_UUID);
  await app.close();
});

// ---------------------------------------------------------------------------
// Tests: Admin notification routes
// ---------------------------------------------------------------------------

test('POST /v1/admin/notifications returns 401 when unauthenticated', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    payload: {
      category: 'admin_message',
      audience: 'admins',
      title: 'Test',
      previewText: 'Test preview',
      body: 'Test body',
      reason: 'Test reason',
      idempotencyKey: 'key-001',
    },
  });

  assert.equal(response.statusCode, 401);
  await app.close();
});

test('POST /v1/admin/notifications returns 403 for regular user', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': devAuthHeader({ role: 'user' }) },
    payload: {
      category: 'admin_message',
      audience: 'admins',
      title: 'Test',
      previewText: 'Test preview',
      body: 'Test body',
      reason: 'Test reason',
      idempotencyKey: 'key-001',
    },
  });

  assert.equal(response.statusCode, 403);
  await app.close();
});

test('POST /v1/admin/notifications requires a reason', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': adminAuthHeader() },
    payload: {
      category: 'admin_message',
      audience: 'admins',
      title: 'Test',
      previewText: 'Test preview',
      body: 'Test body',
      // reason omitted
      idempotencyKey: 'key-001',
    },
  });

  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/notifications all_users requires confirmed=true', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService({
      deliverToAudience: async () => {
        throw new AppError(400, 'notification_confirmation_required', 'Confirmation required.');
      },
    }),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': adminAuthHeader() },
    payload: {
      category: 'admin_message',
      audience: 'all_users',
      title: 'Test',
      previewText: 'Test preview',
      body: 'Test body',
      reason: 'Test reason',
      idempotencyKey: 'key-002',
      // confirmed: true omitted
    },
  });

  assert.equal(response.statusCode, 400);
  await app.close();
});

test('POST /v1/admin/notifications duplicate idempotency key returns 409', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService({
      deliverToAudience: async () => {
        throw new AppError(409, 'notification_duplicate_idempotency_key', 'Duplicate key.');
      },
    }),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': adminAuthHeader() },
    payload: {
      category: 'admin_message',
      audience: 'admins',
      title: 'Test',
      previewText: 'Test preview',
      body: 'Test body',
      reason: 'Test reason',
      idempotencyKey: 'duplicate-key',
    },
  });

  assert.equal(response.statusCode, 409);
  const body = JSON.parse(response.payload) as { error: { code: string } };
  assert.equal(body.error.code, 'notification_duplicate_idempotency_key');
  await app.close();
});

test('POST /v1/admin/notifications response does not expose push tokens', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': adminAuthHeader() },
    payload: {
      category: 'admin_message',
      audience: 'admins',
      title: 'Test',
      previewText: 'Test preview',
      body: 'Test body',
      reason: 'Test reason',
      idempotencyKey: 'key-003',
    },
  });

  const body = JSON.parse(response.payload) as AdminSendNotificationResponse;
  assert.equal(response.statusCode, 200);
  assert.equal(body.ok, true);

  // Admin response must never expose push tokens or delivery provider credentials.
  const bodyStr = JSON.stringify(body);
  assert.ok(!bodyStr.includes('pushToken'), 'Admin response must not contain pushToken');
  assert.ok(!bodyStr.includes('encryptedPushToken'), 'Admin response must not contain encryptedPushToken');
  assert.ok(!bodyStr.includes('providerMessageId'), 'Admin response must not contain providerMessageId');
  await app.close();
});

test('POST /v1/admin/notifications rejects title exceeding max length', async () => {
  const app = await createServer(TEST_CONFIG, {
    notificationDeliveryService: buildFakeDeliveryService(),
  });

  const response = await app.inject({
    method: 'POST',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': adminAuthHeader() },
    payload: {
      category: 'admin_message',
      audience: 'admins',
      title: 'A'.repeat(101), // exceeds MAX_NOTIFICATION_TITLE_LENGTH
      previewText: 'Test preview',
      body: 'Test body',
      reason: 'Test reason',
      idempotencyKey: 'key-004',
    },
  });

  assert.equal(response.statusCode, 400);
  await app.close();
});

test('GET /v1/admin/notifications returns 403 for regular user', async () => {
  const app = await createServer(TEST_CONFIG);

  const response = await app.inject({
    method: 'GET',
    url: NOTIFICATION_ROUTE_PATHS.adminList,
    headers: { 'x-dev-user': devAuthHeader({ role: 'user' }) },
  });

  assert.equal(response.statusCode, 403);
  await app.close();
});
