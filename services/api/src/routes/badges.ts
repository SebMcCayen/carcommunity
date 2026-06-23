/**
 * Badge API routes — Utmärkelser (Awards).
 *
 * Routes:
 *  GET  /v1/users/me/badges
 *  POST /v1/admin/users/:userId/badges/helpful-member
 *  GET  /v1/admin/badges/summary
 *
 * Access control:
 *  - GET /v1/users/me/badges: requires authentication.
 *  - POST /v1/admin/users/:userId/badges/helpful-member: requires admin or owner.
 *  - GET /v1/admin/badges/summary: requires admin or owner.
 *
 * Privacy:
 *  - Badge responses never include other users' data.
 *  - Admin summary contains only aggregate counts — no individual user details.
 *  - No rankings, leaderboards, or competitive fields.
 *  - No speed, distance, or unsafe-driving data.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  BADGE_ROUTE_PATHS,
  buildAdminAwardHelpfulMemberPath,
  type AdminBadgeSummaryResponse,
  type AwardHelpfulMemberResponse,
  type CurrentUserBadgesResponse,
} from '@carcommunity/shared/badges';

import { requireAdminHook, requireAuthHook } from '../lib/auth-context.js';
import { BadgeService } from '../lib/badge-service.js';
import { AppError } from '../lib/errors.js';
import { canAccessAdminFeatures } from '@carcommunity/shared/users';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const userIdParamsSchema = z.object({ userId: z.string().uuid() }).strict();

const awardHelpfulMemberBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
  })
  .strict();

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export interface RegisterBadgeRoutesDependencies {
  badgeService?: BadgeService;
}

export async function registerBadgeRoutes(
  app: FastifyInstance,
  dependencies: RegisterBadgeRoutesDependencies = {},
): Promise<void> {
  const badgeService = dependencies.badgeService ?? new BadgeService(app.prisma, null);

  /**
   * GET /v1/users/me/badges
   * Returns the current user's awarded badges.
   * Requires authentication. Never returns other users' badges.
   */
  app.get(
    BADGE_ROUTE_PATHS.myBadges,
    { preHandler: requireAuthHook },
    async (request): Promise<CurrentUserBadgesResponse> => {
      const auth = request.auth!;
      const badges = await badgeService.getCurrentUserBadges(auth.userId);
      return { ok: true, data: { badges } };
    },
  );

  /**
   * POST /v1/admin/users/:userId/badges/helpful-member
   * Manually awards the helpful_member badge. Admin or owner only.
   * Requires a non-empty reason. Writes an audit log entry.
   */
  app.post(
    buildAdminAwardHelpfulMemberPath(':userId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AwardHelpfulMemberResponse> => {
      const auth = request.auth!;

      if (!canAccessAdminFeatures({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = userIdParamsSchema.parse(request.params);
      const body = awardHelpfulMemberBodySchema.parse(request.body);

      const result = await badgeService.awardHelpfulMemberByAdmin({
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
        },
        targetUserId: params.userId,
        reason: body.reason,
      });

      return {
        ok: true,
        data: {
          badge: result.badge,
          alreadyAwarded: result.alreadyAwarded,
        },
      };
    },
  );

  /**
   * GET /v1/admin/badges/summary
   * Returns aggregate badge counts. Admin or owner only.
   * Never exposes a leaderboard or individual user data.
   */
  app.get(
    BADGE_ROUTE_PATHS.adminBadgeSummary,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminBadgeSummaryResponse> => {
      const auth = request.auth!;

      if (!canAccessAdminFeatures({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const summary = await badgeService.getAdminBadgeSummary();
      return { ok: true, data: { summary } };
    },
  );
}
