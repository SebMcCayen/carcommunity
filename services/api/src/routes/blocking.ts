import {
  BLOCKING_ROUTE_PATHS,
  DEFAULT_BLOCKED_USERS_PAGE_SIZE,
  MAX_BLOCKED_USERS_PAGE_SIZE,
  type BlockUserResponse,
  type BlockedUsersListResponse,
  type UnblockUserResponse,
} from '@carcommunity/shared/blocking';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { requireAuthHook } from '../lib/auth-context.js';
import { BlockingService } from '../lib/blocking-service.js';

const userIdParamsSchema = z
  .object({
    userId: z.string().uuid(),
  })
  .strict();

const blockedUsersQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).default(1),
    pageSize: z
      .coerce.number()
      .int()
      .min(1)
      .max(MAX_BLOCKED_USERS_PAGE_SIZE)
      .default(DEFAULT_BLOCKED_USERS_PAGE_SIZE),
  })
  .strict();

export interface RegisterBlockingRoutesDependencies {
  blockingService?: BlockingService;
}

export async function registerBlockingRoutes(
  app: FastifyInstance,
  dependencies: RegisterBlockingRoutesDependencies = {},
): Promise<void> {
  const blockingService = dependencies.blockingService ?? new BlockingService(app.prisma);

  // ---------------------------------------------------------------------------
  // POST /v1/users/:userId/block — block a user
  // ---------------------------------------------------------------------------
  app.post(
    BLOCKING_ROUTE_PATHS.userBlock(':userId'),
    { preHandler: requireAuthHook },
    async (request): Promise<BlockUserResponse> => {
      const auth = request.auth!;
      const params = userIdParamsSchema.parse(request.params);

      const result = await blockingService.blockUser({
        blockerUserId: auth.userId,
        targetUserId: params.userId,
      });

      return {
        ok: true,
        data: {
          block: result.block,
          shouldRefreshMarkers: true,
        },
      };
    },
  );

  // ---------------------------------------------------------------------------
  // DELETE /v1/users/:userId/block — unblock a user
  // ---------------------------------------------------------------------------
  app.delete(
    BLOCKING_ROUTE_PATHS.userBlock(':userId'),
    { preHandler: requireAuthHook },
    async (request): Promise<UnblockUserResponse> => {
      const auth = request.auth!;
      const params = userIdParamsSchema.parse(request.params);

      const result = await blockingService.unblockUser({
        blockerUserId: auth.userId,
        targetUserId: params.userId,
      });

      return {
        ok: true,
        data: {
          unblocked: result.unblocked,
        },
      };
    },
  );

  // ---------------------------------------------------------------------------
  // GET /v1/users/me/blocked-users — list users blocked by the current user
  // ---------------------------------------------------------------------------
  app.get(
    BLOCKING_ROUTE_PATHS.myBlockedUsers,
    { preHandler: requireAuthHook },
    async (request): Promise<BlockedUsersListResponse> => {
      const auth = request.auth!;
      const query = blockedUsersQuerySchema.parse(request.query);

      const result = await blockingService.listBlockedUsers({
        blockerUserId: auth.userId,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: {
          blockedUsers: result.blockedUsers,
        },
        meta: {
          page: query.page,
          pageSize: query.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );
}
