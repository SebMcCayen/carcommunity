/**
 * Admin notification API routes.
 *
 * Routes:
 *  POST /v1/admin/notifications             — send a notification batch
 *  GET  /v1/admin/notifications             — list admin notification batches
 *  GET  /v1/admin/notifications/:batchId    — get batch detail
 *
 * Access control:
 *  - All routes require admin or owner role.
 *  - all_users / free_users audiences require explicit confirmation.
 *  - event_participants requires a valid eventId.
 *  - specific_user requires a valid targetUserId.
 *  - Reason is mandatory; written to the audit log.
 *  - Idempotency key prevents duplicate sends.
 *  - Owner-only approval should be added for all_users audience if product requirements change.
 *
 * Privacy and security:
 *  - No push tokens in responses.
 *  - No delivery provider credentials.
 *  - No full recipient lists exposed.
 *  - No arbitrary HTML in body.
 *  - No arbitrary external URLs.
 *  - Body is plain text only.
 *  - Audit log written for every send.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  ACTIVE_NOTIFICATION_CATEGORIES,
  ADMIN_NOTIFICATION_AUDIENCES,
  NOTIFICATION_ACTION_TYPES,
  DEFAULT_NOTIFICATION_PAGE_SIZE,
  MAX_NOTIFICATION_PAGE_SIZE,
  MAX_NOTIFICATION_TITLE_LENGTH,
  MAX_NOTIFICATION_PREVIEW_LENGTH,
  MAX_NOTIFICATION_BODY_LENGTH,
  NOTIFICATION_ROUTE_PATHS,
  buildAdminNotificationDetailPath,
  type AdminSendNotificationResponse,
  type PaginatedAdminNotificationBatchesResponse,
  type AdminNotificationBatchDetailResponse,
} from '@carcommunity/shared/notifications';

import { requireAdminHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import type { ModerationService } from '../lib/moderation-service.js';
import { NotificationDeliveryService } from '../lib/notification-delivery-service.js';
import type { PushNotificationProvider } from '../lib/push-provider.js';
import { NotificationService } from '../lib/notification-service.js';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const adminSendBodySchema = z
  .object({
    category: z.enum(ACTIVE_NOTIFICATION_CATEGORIES),
    audience: z.enum(ADMIN_NOTIFICATION_AUDIENCES),
    title: z
      .string().trim()
      .min(1)
      .max(MAX_NOTIFICATION_TITLE_LENGTH)
      .refine((v) => !/[<>]/.test(v), { message: 'HTML is not allowed.' })
      .refine((v) => !/(https?:\/\/|www\.)/i.test(v), { message: 'External URLs are not allowed.' }),
    previewText: z
      .string().trim()
      .min(1)
      .max(MAX_NOTIFICATION_PREVIEW_LENGTH)
      .refine((v) => !/[<>]/.test(v), { message: 'HTML is not allowed.' })
      .refine((v) => !/(https?:\/\/|www\.)/i.test(v), { message: 'External URLs are not allowed.' }),
    body: z
      .string().trim()
      .min(1)
      .max(MAX_NOTIFICATION_BODY_LENGTH)
      .refine((v) => !/[<>]/.test(v), { message: 'HTML is not allowed.' })
      .refine((v) => !/(https?:\/\/|www\.)/i.test(v), { message: 'External URLs are not allowed.' }),
    actionType: z.enum(NOTIFICATION_ACTION_TYPES).optional(),
    eventId: z.string().uuid().optional(),
    targetUserId: z.string().uuid().optional(),
    reason: z.string().min(1, 'Reason is required.').max(1000),
    idempotencyKey: z.string().trim().min(1).max(255),
    confirmed: z.boolean().optional(),
  })
  .strict();

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

const batchIdParamsSchema = z
  .object({ batchId: z.string().uuid() })
  .strict();

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export interface RegisterAdminNotificationRoutesDependencies {
  notificationService?: NotificationService;
  deliveryService?: NotificationDeliveryService;
  moderationService?: ModerationService;
  pushProvider?: PushNotificationProvider;
  pushNotificationsFeatureEnabled?: boolean;
}

export async function registerAdminNotificationRoutes(
  app: FastifyInstance,
  dependencies: RegisterAdminNotificationRoutesDependencies = {},
): Promise<void> {
  const notificationService =
    dependencies.notificationService ?? new NotificationService(app.prisma);

  const deliveryService =
    dependencies.deliveryService ??
    new NotificationDeliveryService(app.prisma, {
      notificationService,
      pushProvider: dependencies.pushProvider,
      pushNotificationsFeatureEnabled: dependencies.pushNotificationsFeatureEnabled,
    });

  // -------------------------------------------------------------------------
  // POST /v1/admin/notifications
  // Send a notification to a bounded audience.
  // Requires admin or owner. Reason is mandatory. Audit log written.
  // all_users and free_users require confirmed=true.
  // Idempotency key prevents duplicate sends.
  // -------------------------------------------------------------------------
  app.post(
    NOTIFICATION_ROUTE_PATHS.adminList,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminSendNotificationResponse> => {
      const auth = request.auth!;
      const body = adminSendBodySchema.parse(request.body);

      // Validate audience-specific requirements.
      if (body.audience === 'event_participants' && !body.eventId) {
        throw new AppError(
          400,
          'notification_event_required',
          'eventId is required for event_participants audience.',
        );
      }

      if (body.audience === 'specific_user' && !body.targetUserId) {
        throw new AppError(
          400,
          'notification_target_user_required',
          'targetUserId is required for specific_user audience.',
        );
      }

      // Guard: reason is required.
      if (!body.reason || body.reason.trim().length === 0) {
        throw new AppError(400, 'notification_reason_required', 'Reason is required.');
      }

      const result = await deliveryService.deliverToAudience({
        audience: body.audience,
        category: body.category,
        title: body.title,
        previewText: body.previewText,
        body: body.body,
        actionType: body.actionType,
        relatedEntityId: body.eventId ?? body.targetUserId,
        reason: body.reason,
        idempotencyKey: body.idempotencyKey,
        createdByUserId: auth.userId,
        eventId: body.eventId,
        targetUserId: body.targetUserId,
        confirmed: body.confirmed,
      });

      // Write audit log.
      // Prefer ModerationService if provided (non-blocking), otherwise write directly via prisma.
      try {
        if (dependencies.moderationService) {
          await dependencies.moderationService.writeAuditLog({
            actorUserId: auth.userId,
            action: 'admin_notification_sent',
            entityType: 'admin_notification_batch',
            entityId: result.batchId,
            reason: body.reason,
            metadata: {
              category: body.category,
              audience: body.audience,
              recipientCount: result.recipientCount,
            },
          });
        } else {
          await writeNotificationAuditLog(app.prisma, {
            actorUserId: auth.userId,
            batchId: result.batchId,
            category: body.category,
            audience: body.audience,
            recipientCount: result.recipientCount,
            reason: body.reason,
          });
        }
      } catch (error) {
        request.log.error(
          { err: error, batchId: result.batchId, actorUserId: auth.userId },
          'Failed to write admin notification audit log',
        );
      }

      return {
        ok: true,
        data: {
          batchId: result.batchId,
          audience: body.audience,
          recipientCount: result.recipientCount,
          createdAt: result.createdAt.toISOString(),
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // GET /v1/admin/notifications
  // List admin notification batches, newest first.
  // -------------------------------------------------------------------------
  app.get(
    NOTIFICATION_ROUTE_PATHS.adminList,
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedAdminNotificationBatchesResponse> => {
      const query = paginationQuerySchema.parse(request.query);
      const skip = (query.page - 1) * query.pageSize;

      const [batches, total] = await app.prisma.$transaction([
        app.prisma.adminNotificationBatch.findMany({
          orderBy: { createdAt: 'desc' },
          skip,
          take: query.pageSize,
        }),
        app.prisma.adminNotificationBatch.count(),
      ]);

      return {
        ok: true,
        data: {
          batches: batches.map((b) => ({
            batchId: b.id,
            category: b.category as (typeof ACTIVE_NOTIFICATION_CATEGORIES)[number],
            audience: b.audience as import('@carcommunity/shared/notifications').AdminNotificationAudience,
            title: b.title,
            recipientCount: b.recipientCount,
            reason: b.reason,
            createdAt: b.createdAt.toISOString(),
            createdByUserId: b.createdByUserId,
          })),
        },
        meta: {
          page: query.page,
          pageSize: query.pageSize,
          total,
          hasNext: skip + batches.length < total,
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // GET /v1/admin/notifications/:batchId
  // Get a single batch detail.
  // -------------------------------------------------------------------------
  app.get(
    buildAdminNotificationDetailPath(':batchId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminNotificationBatchDetailResponse> => {
      const params = batchIdParamsSchema.parse(request.params);

      const batch = await app.prisma.adminNotificationBatch.findUnique({
        where: { id: params.batchId },
      });

      if (!batch) {
        throw new AppError(404, 'notification_not_found', 'Notification batch not found.');
      }

      return {
        ok: true,
        data: {
          batchId: batch.id,
          category: batch.category as (typeof ACTIVE_NOTIFICATION_CATEGORIES)[number],
          audience: batch.audience as import('@carcommunity/shared/notifications').AdminNotificationAudience,
          title: batch.title,
          recipientCount: batch.recipientCount,
          reason: batch.reason,
          createdAt: batch.createdAt.toISOString(),
          createdByUserId: batch.createdByUserId,
        },
      };
    },
  );
}

// ---------------------------------------------------------------------------
// Audit log helper
// ---------------------------------------------------------------------------

async function writeNotificationAuditLog(
  prisma: FastifyInstance['prisma'],
  input: {
    actorUserId: string;
    batchId: string;
    category: string;
    audience: string;
    recipientCount: number;
    reason: string;
  },
): Promise<void> {
  await prisma.auditLog.create({
    data: {
      actorUserId: input.actorUserId,
      action: 'admin_notification_sent',
      entityType: 'admin_notification_batch',
      entityId: input.batchId,
      reason: input.reason,
      metadata: {
        // Audit metadata must not include push tokens, full recipient lists,
        // exact locations, session data, or personal data.
        category: input.category,
        audience: input.audience,
        recipientCount: input.recipientCount,
      },
    },
  });
}
