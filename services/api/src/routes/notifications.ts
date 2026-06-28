/**
 * Notification API routes.
 *
 * User routes:
 *  GET  /v1/notifications                           — paginated in-app inbox
 *  GET  /v1/notifications/:notificationId           — single notification detail
 *  POST /v1/notifications/:notificationId/read      — mark one as read (idempotent)
 *  POST /v1/notifications/read-all                  — mark all as read
 *  POST /v1/notifications/devices                   — register push device
 *  DELETE /v1/notifications/devices/:deviceId       — unregister push device
 *  GET  /v1/users/me/notification-preferences       — get preferences
 *  PATCH /v1/users/me/notification-preferences      — update preferences
 *
 * Access control:
 *  - All routes require an authenticated session.
 *  - Most routes require an active (non-deleted, non-suspended) account.
 *  - Device unregistration uses requireAuthenticatedHook so cleanup still works if feature access is lost.
 *  - User ID is always derived from the authenticated session.
 *  - Raw push tokens must never be returned in API responses.
 *  - Users may only access their own notifications and preferences.
 *
 * Feature flag:
 *  - pushNotifications flag gates device registration and push setup.
 *  - In-app routes remain accessible when push is disabled.
 *
 * Privacy:
 *  - No push tokens in responses.
 *  - No delivery provider credentials.
 *  - No other users' notifications.
 *  - No exact locations in responses.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';
import {
  ACTIVE_NOTIFICATION_CATEGORIES,
  NOTIFICATION_PLATFORMS,
  DEFAULT_NOTIFICATION_PAGE_SIZE,
  MAX_NOTIFICATION_PAGE_SIZE,
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
  type GetNotificationPreferencesResponse,
  type PatchNotificationPreferencesResponse,
} from '@carcommunity/shared/notifications';

import { requireAuthHook, requireAuthenticatedHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { NotificationService } from '../lib/notification-service.js';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const paginationQuerySchema = z
  .object({
    page: z
      .string()
      .optional()
      .transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z
      .string()
      .optional()
      .transform((v) =>
        v
          ? Math.min(MAX_NOTIFICATION_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_NOTIFICATION_PAGE_SIZE))
          : DEFAULT_NOTIFICATION_PAGE_SIZE,
      ),
  })
  .strict();

const notificationIdParamsSchema = z
  .object({ notificationId: z.string().uuid() })
  .strict();

const deviceIdParamsSchema = z
  .object({ deviceId: z.string().uuid() })
  .strict();

/**
 * Push token validation: conservative whitelist.
 * Accept alphanumeric, hyphen, underscore, colon, period, and slash characters.
 * Max 512 chars. Min 10 chars.
 * This covers Expo push tokens (ExponentPushToken[...]) and raw APNs/FCM token formats.
 * Never log the token value.
 */
const PUSH_TOKEN_REGEX = /^[A-Za-z0-9\-_.:/[\]]+$/;

const registerDeviceBodySchema = z
  .object({
    platform: z.enum(NOTIFICATION_PLATFORMS),
    pushToken: z
      .string()
      .min(10, 'Push token too short.')
      .max(512, 'Push token too long.')
      .regex(PUSH_TOKEN_REGEX, 'Push token contains invalid characters.'),
    appVersion: z.string().max(50).optional(),
    buildNumber: z.string().max(50).optional(),
  })
  .strict();

const preferencesUpdateBodySchema = z
  .object({
    preferences: z
      .array(
        z.object({
          category: z.enum(ACTIVE_NOTIFICATION_CATEGORIES),
          pushEnabled: z.boolean().optional(),
          inAppEnabled: z.boolean().optional(),
        }).strict(),
      )
      .min(1)
      .max(20)
      .refine((prefs) => new Set(prefs.map((p) => p.category)).size === prefs.length, {
        message: 'Duplicate categories are not allowed.',
      }),
  })
  .strict();

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export interface RegisterNotificationRoutesDependencies {
  notificationService?: NotificationService;
  pushNotificationsFeatureEnabled?: boolean;
}

export async function registerNotificationRoutes(
  app: FastifyInstance,
  dependencies: RegisterNotificationRoutesDependencies = {},
): Promise<void> {
  const notificationService =
    dependencies.notificationService ?? new NotificationService(app.prisma);
  const pushNotificationsEnabled =
    dependencies.pushNotificationsFeatureEnabled ?? DEFAULT_FEATURE_FLAGS.pushNotifications;

  function assertPushEnabled(): void {
    if (!pushNotificationsEnabled) {
      throw new AppError(403, 'notification_feature_disabled', 'Push notifications feature is disabled.');
    }
  }

  // -------------------------------------------------------------------------
  // GET /v1/notifications
  // Paginated in-app notification inbox for the current user.
  // -------------------------------------------------------------------------
  app.get(
    NOTIFICATION_ROUTE_PATHS.list,
    { preHandler: requireAuthHook },
    async (request): Promise<PaginatedNotificationsResponse> => {
      const auth = request.auth!;
      const query = paginationQuerySchema.parse(request.query);

      const result = await notificationService.listNotifications({
        userId: auth.userId,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: {
          notifications: result.notifications,
          unreadCount: result.unreadCount,
        },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // GET /v1/notifications/:notificationId
  // Single notification detail. Ownership enforced.
  // Protected details must be re-validated after app open.
  // -------------------------------------------------------------------------
  app.get(
    buildNotificationDetailPath(':notificationId'),
    { preHandler: requireAuthHook },
    async (request): Promise<NotificationDetailResponse> => {
      const auth = request.auth!;
      const params = notificationIdParamsSchema.parse(request.params);

      const detail = await notificationService.getNotificationDetail({
        userId: auth.userId,
        notificationId: params.notificationId,
      });

      return { ok: true, data: detail };
    },
  );

  // -------------------------------------------------------------------------
  // POST /v1/notifications/:notificationId/read
  // Mark one notification as read. Idempotent.
  // -------------------------------------------------------------------------
  app.post(
    buildNotificationReadPath(':notificationId'),
    { preHandler: requireAuthHook },
    async (request): Promise<MarkNotificationReadResponse> => {
      const auth = request.auth!;
      const params = notificationIdParamsSchema.parse(request.params);

      const result = await notificationService.markRead({
        userId: auth.userId,
        notificationId: params.notificationId,
      });

      return {
        ok: true,
        data: {
          notificationId: result.notificationId,
          readAt: result.readAt.toISOString(),
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // POST /v1/notifications/read-all
  // Mark all unread notifications as read.
  // -------------------------------------------------------------------------
  app.post(
    NOTIFICATION_ROUTE_PATHS.readAll,
    { preHandler: requireAuthHook },
    async (request): Promise<MarkAllNotificationsReadResponse> => {
      const auth = request.auth!;

      const result = await notificationService.markAllRead({ userId: auth.userId });

      return { ok: true, data: { markedCount: result.markedCount } };
    },
  );

  // -------------------------------------------------------------------------
  // POST /v1/notifications/devices
  // Register a push device token. Requires push feature flag.
  // Raw token is never returned; only the opaque deviceId.
  // -------------------------------------------------------------------------
  app.post(
    NOTIFICATION_ROUTE_PATHS.registerDevice,
    { preHandler: requireAuthHook },
    async (request): Promise<RegisterPushDeviceResponse> => {
      const auth = request.auth!;
      assertPushEnabled();

      const body = registerDeviceBodySchema.parse(request.body);

      const result = await notificationService.registerDevice({
        userId: auth.userId,
        platform: body.platform,
        pushToken: body.pushToken,
        appVersion: body.appVersion,
        buildNumber: body.buildNumber,
      });

      return {
        ok: true,
        data: {
          deviceId: result.deviceId,
          platform: result.platform,
          registeredAt: result.registeredAt.toISOString(),
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // DELETE /v1/notifications/devices/:deviceId
  // Unregister a push device. Requires authentication only (not full auth hook)
  // so the device can be cleaned up even if the user is suspended.
  // -------------------------------------------------------------------------
  app.delete(
    buildNotificationDevicePath(':deviceId'),
    { preHandler: requireAuthenticatedHook },
    async (request): Promise<UnregisterPushDeviceResponse> => {
      const auth = request.auth!;
      const params = deviceIdParamsSchema.parse(request.params);

      const result = await notificationService.unregisterDevice({
        userId: auth.userId,
        deviceId: params.deviceId,
      });

      return { ok: true, data: result };
    },
  );

  // -------------------------------------------------------------------------
  // GET /v1/users/me/notification-preferences
  // Get current user's notification preferences.
  // -------------------------------------------------------------------------
  app.get(
    NOTIFICATION_ROUTE_PATHS.preferences,
    { preHandler: requireAuthHook },
    async (request): Promise<GetNotificationPreferencesResponse> => {
      const auth = request.auth!;

      const preferences = await notificationService.getPreferences({ userId: auth.userId });

      return { ok: true, data: { preferences } };
    },
  );

  // -------------------------------------------------------------------------
  // PATCH /v1/users/me/notification-preferences
  // Update current user's notification preferences.
  // -------------------------------------------------------------------------
  app.patch(
    NOTIFICATION_ROUTE_PATHS.preferences,
    { preHandler: requireAuthHook },
    async (request): Promise<PatchNotificationPreferencesResponse> => {
      const auth = request.auth!;
      const body = preferencesUpdateBodySchema.parse(request.body);

      const preferences = await notificationService.updatePreferences({
        userId: auth.userId,
        updates: body.preferences,
      });

      return { ok: true, data: { preferences } };
    },
  );
}
