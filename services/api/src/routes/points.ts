/**
 * Points API routes — Kronpoäng (KP) wallet.
 *
 * Routes:
 *  GET  /v1/points/balance
 *  GET  /v1/points/ledger
 *  POST /v1/admin/users/:userId/points/adjust
 *  GET  /v1/admin/users/:userId/points/balance
 *  GET  /v1/admin/users/:userId/points/ledger
 *
 * Access control:
 *  - GET /v1/points/balance: requires authentication. Deleted users denied.
 *    Suspended users may view their balance.
 *  - GET /v1/points/ledger: requires authentication. Deleted users denied.
 *    Returns only the current user's entries.
 *  - POST /v1/admin/users/:userId/points/adjust: requires admin or owner.
 *    Writes an audit log. Debit rejected if balance would go negative.
 *  - GET /v1/admin/users/:userId/points/balance: requires admin or owner.
 *  - GET /v1/admin/users/:userId/points/ledger: requires admin or owner.
 *
 * Privacy:
 *  - Balance and ledger responses never include another user's data.
 *  - No purchase, transfer, withdrawal, or cash-value fields.
 *  - No public leaderboards.
 *  - No generic earn/spend endpoints.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  POINTS_ROUTE_PATHS,
  buildAdminPointsAdjustPath,
  buildAdminUserPointsBalancePath,
  buildAdminUserPointsLedgerPath,
  DEFAULT_POINTS_PAGE_SIZE,
  MAX_POINTS_PAGE_SIZE,
  MAX_ADMIN_ADJUSTMENT_AMOUNT,
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
  type AdminPointsAdjustmentResponse,
} from '@carcommunity/shared/points';
import { canAccessAdminFeatures, isSuspendedStatus } from '@carcommunity/shared/users';

import { requireAuthenticatedHook, requireAdminHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { PointsService } from '../lib/points-service.js';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const ledgerQuerySchema = z
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
          ? Math.min(MAX_POINTS_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_POINTS_PAGE_SIZE))
          : DEFAULT_POINTS_PAGE_SIZE,
      ),
  })
  .strict();

const adminUserIdParamsSchema = z.object({ userId: z.string().uuid() }).strict();

const adminAdjustmentBodySchema = z
  .object({
    type: z.enum(['adjustment_credit', 'adjustment_debit']),
    amount: z
      .number()
      .int('Amount must be an integer.')
      .positive('Amount must be a positive integer.')
      .max(MAX_ADMIN_ADJUSTMENT_AMOUNT, `Amount must not exceed ${MAX_ADMIN_ADJUSTMENT_AMOUNT} KP.`),
    reason: z.string().min(1, 'Reason is required.').max(500, 'Reason must not exceed 500 characters.'),
  })
  .strict();

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export interface RegisterPointsRoutesDependencies {
  pointsService?: PointsService;
}

export async function registerPointsRoutes(
  app: FastifyInstance,
  dependencies: RegisterPointsRoutesDependencies = {},
): Promise<void> {
  const pointsService = dependencies.pointsService ?? new PointsService(app.prisma);

  /**
   * GET /v1/points/balance
   * Returns the current user's KP balance.
   *
   * Requires authentication. Deleted users receive 403.
   * Suspended users may view their existing balance (account-limited UI).
   * Never exposes another user's balance.
   */
  app.get(
    POINTS_ROUTE_PATHS.balance,
    { preHandler: requireAuthenticatedHook },
    async (request): Promise<PointsBalanceResponse> => {
      const auth = request.auth!;

      if (auth.status === 'deleted') {
        throw new AppError(403, 'forbidden', 'Your account has been deleted.');
      }

      const balance = await pointsService.getPointsBalance(auth.userId);

      return {
        ok: true,
        data: {
          balance,
          displayName: 'Kronpoäng',
          shortForm: 'KP',
        },
      };
    },
  );

  /**
   * GET /v1/points/ledger
   * Returns the current user's paginated ledger entries, newest first.
   *
   * Requires authentication. Deleted users receive 403.
   * Returns only the current user's entries.
   * Returns the authoritative balance alongside entries.
   */
  app.get(
    POINTS_ROUTE_PATHS.ledger,
    { preHandler: requireAuthenticatedHook },
    async (request): Promise<PaginatedPointsLedgerResponse> => {
      const auth = request.auth!;

      if (auth.status === 'deleted') {
        throw new AppError(403, 'forbidden', 'Your account has been deleted.');
      }

      const query = ledgerQuerySchema.parse(request.query);

      const result = await pointsService.listPointsLedger({
        userId: auth.userId,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: {
          balance: result.balance,
          transactions: result.transactions,
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

  /**
   * POST /v1/admin/users/:userId/points/adjust
   * Applies a credit or debit adjustment to a user's KP balance.
   *
   * Requires admin or owner role.
   * Requires a positive integer amount and a mandatory reason.
   * Debit is rejected if it would produce a negative balance.
   * Writes an audit log entry.
   * Does not allow setting an absolute balance.
   * Protects owner accounts from non-owner actors.
   */
  app.post(
    buildAdminPointsAdjustPath(':userId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminPointsAdjustmentResponse> => {
      const auth = request.auth!;

      if (!canAccessAdminFeatures({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      if (isSuspendedStatus(auth.status)) {
        throw new AppError(403, 'suspended', 'Your account has been suspended.');
      }

      const params = adminUserIdParamsSchema.parse(request.params);
      const body = adminAdjustmentBodySchema.parse(request.body);

      const entry = await pointsService.applyAdminPointsAdjustment({
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
        },
        targetUserId: params.userId,
        type: body.type,
        amount: body.amount,
        reason: body.reason,
      });

      return {
        ok: true,
        data: {
          transactionId: entry.transactionId,
          transactionType: entry.transactionType,
          amount: entry.amount,
          balanceAfter: entry.balanceAfter,
          createdAt: entry.createdAt,
        },
      };
    },
  );

  /**
   * GET /v1/admin/users/:userId/points/balance
   * Returns the KP balance for a specific user.
   *
   * Requires admin or owner role.
   * The balance is authoritative — never calculated on the client.
   */
  app.get(
    buildAdminUserPointsBalancePath(':userId'),
    { preHandler: requireAdminHook },
    async (request): Promise<PointsBalanceResponse> => {
      const auth = request.auth!;

      if (!canAccessAdminFeatures({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = adminUserIdParamsSchema.parse(request.params);
      const balance = await pointsService.getPointsBalance(params.userId);

      return {
        ok: true,
        data: { balance, displayName: 'Kronpoäng', shortForm: 'KP' },
      };
    },
  );

  /**
   * GET /v1/admin/users/:userId/points/ledger
   * Returns a paginated KP ledger for a specific user.
   *
   * Requires admin or owner role.
   */
  app.get(
    buildAdminUserPointsLedgerPath(':userId'),
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedPointsLedgerResponse> => {
      const auth = request.auth!;

      if (!canAccessAdminFeatures({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = adminUserIdParamsSchema.parse(request.params);
      const query = ledgerQuerySchema.parse(request.query);

      const result = await pointsService.listPointsLedger({
        userId: params.userId,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: {
          balance: result.balance,
          transactions: result.transactions,
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
}
