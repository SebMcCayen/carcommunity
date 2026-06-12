import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { requireAdminHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { ModerationService } from '../lib/moderation-service.js';
import type { AuditLogListResponse, AuditLogSummary, ModerationResponse } from '@carcommunity/shared/users';

const userIdParamsSchema = z
  .object({
    userId: z.string().uuid(),
  })
  .strict();

const warnBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
  })
  .strict();

const suspendTemporaryBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
    expiresAt: z.string().datetime(),
  })
  .strict();

const suspendPermanentBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
  })
  .strict();

const restoreAccessBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
  })
  .strict();

const auditLogQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional(),
  })
  .strict();

export interface RegisterModerationRoutesDependencies {
  moderationService?: ModerationService;
}

export async function registerModerationRoutes(
  app: FastifyInstance,
  dependencies: RegisterModerationRoutesDependencies = {},
): Promise<void> {
  const moderationService = dependencies.moderationService ?? new ModerationService(app.prisma);

  /**
   * POST /v1/admin/users/:userId/warn
   * Issues a warning to the target user.
   * Requires admin or owner role. Reason is mandatory.
   */
  app.post(
    '/v1/admin/users/:userId/warn',
    { preHandler: requireAdminHook },
    async (request): Promise<ModerationResponse> => {
      const auth = request.auth!;
      const params = userIdParamsSchema.parse(request.params);
      const body = warnBodySchema.parse(request.body);

      const action = await moderationService.warnUser({
        actor: { userId: auth.userId, role: auth.role },
        targetUserId: params.userId,
        reason: body.reason,
      });

      return { ok: true, data: { action } };
    },
  );

  /**
   * POST /v1/admin/users/:userId/suspend-temporary
   * Temporarily suspends the target user.
   * Requires admin or owner role. Reason and expiresAt are mandatory.
   */
  app.post(
    '/v1/admin/users/:userId/suspend-temporary',
    { preHandler: requireAdminHook },
    async (request): Promise<ModerationResponse> => {
      const auth = request.auth!;
      const params = userIdParamsSchema.parse(request.params);
      const body = suspendTemporaryBodySchema.parse(request.body);

      const action = await moderationService.suspendTemporary({
        actor: { userId: auth.userId, role: auth.role },
        targetUserId: params.userId,
        reason: body.reason,
        expiresAt: body.expiresAt,
      });

      return { ok: true, data: { action } };
    },
  );

  /**
   * POST /v1/admin/users/:userId/suspend-permanent
   * Permanently suspends the target user.
   * Requires admin or owner role. Reason is mandatory.
   */
  app.post(
    '/v1/admin/users/:userId/suspend-permanent',
    { preHandler: requireAdminHook },
    async (request): Promise<ModerationResponse> => {
      const auth = request.auth!;
      const params = userIdParamsSchema.parse(request.params);
      const body = suspendPermanentBodySchema.parse(request.body);

      const action = await moderationService.suspendPermanent({
        actor: { userId: auth.userId, role: auth.role },
        targetUserId: params.userId,
        reason: body.reason,
      });

      return { ok: true, data: { action } };
    },
  );

  /**
   * POST /v1/admin/users/:userId/restore-access
   * Restores access for a warned or suspended user.
   * Requires admin or owner role. Reason is mandatory.
   */
  app.post(
    '/v1/admin/users/:userId/restore-access',
    { preHandler: requireAdminHook },
    async (request): Promise<ModerationResponse> => {
      const auth = request.auth!;
      const params = userIdParamsSchema.parse(request.params);
      const body = restoreAccessBodySchema.parse(request.body);

      const action = await moderationService.restoreAccess({
        actor: { userId: auth.userId, role: auth.role },
        targetUserId: params.userId,
        reason: body.reason,
      });

      return { ok: true, data: { action } };
    },
  );

  /**
   * GET /v1/admin/audit-log
   * Returns a paginated list of audit log entries.
   * Requires admin or owner role.
   * Does not expose sensitive metadata or session data.
   */
  app.get(
    '/v1/admin/audit-log',
    { preHandler: requireAdminHook },
    async (request): Promise<AuditLogListResponse> => {
      const query = auditLogQuerySchema.parse(request.query);
      const page = query.page ?? 1;
      const pageSize = query.pageSize ?? 20;
      const skip = (page - 1) * pageSize;

      const [entries, total] = await app.prisma.$transaction([
        app.prisma.auditLog.findMany({
          skip,
          take: pageSize,
          orderBy: { createdAt: 'desc' },
          select: {
            id: true,
            actorUserId: true,
            action: true,
            entityType: true,
            entityId: true,
            reason: true,
            createdAt: true,
            // metadata is intentionally excluded to avoid exposing sensitive data
          },
        }),
        app.prisma.auditLog.count(),
      ]);

      if (!entries || !Array.isArray(entries)) {
        throw new AppError(500, 'internal_error', 'Failed to retrieve audit log.');
      }

      const safeEntries: AuditLogSummary[] = entries.map((entry) => ({
        id: entry.id,
        actorUserId: entry.actorUserId,
        action: entry.action,
        entityType: entry.entityType,
        entityId: entry.entityId,
        reason: entry.reason,
        createdAt: entry.createdAt.toISOString(),
      }));

      return {
        ok: true,
        data: { entries: safeEntries },
        meta: {
          page,
          pageSize,
          total,
          hasNext: skip + pageSize < total,
        },
      };
    },
  );
}
