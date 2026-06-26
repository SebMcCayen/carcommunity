/**
 * Crown Hunt (Kronjakt) API routes.
 *
 * Routes:
 *  GET  /v1/crown-hunt/points
 *  GET  /v1/crown-hunt/points/:pointId
 *  POST /v1/crown-hunt/points/:pointId/claim
 *  GET  /v1/crown-hunt/me/claims
 *  GET  /v1/admin/crown-hunt/points
 *  POST /v1/admin/crown-hunt/points
 *  PATCH /v1/admin/crown-hunt/points/:pointId
 *  POST /v1/admin/crown-hunt/points/:pointId/activate
 *  POST /v1/admin/crown-hunt/points/:pointId/pause
 *  GET  /v1/admin/crown-hunt/claims
 *
 * Access control:
 *  - Mobile point/claim routes: requireMemberHook (active member_monthly).
 *  - Admin routes: requireAdminHook (admin or owner role).
 *
 * Privacy:
 *  - Claim responses never expose anti-fraud thresholds or raw risk metadata.
 *  - Claim history never includes exact claim coordinates.
 *  - Mobile responses never include internal fraud signals.
 *  - No public leaderboards.
 *  - No route history.
 *
 * Safety:
 *  - Claims are never automatic. The user must explicitly press collect.
 *  - Reward amount must NOT be accepted from the client.
 *  - userId is always derived from the authenticated session.
 *  - All inputs are validated by the service layer.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  CROWN_HUNT_ROUTE_PATHS,
  buildCrownHuntPointPath,
  buildCrownHuntClaimPath,
  buildAdminCrownHuntPointPath,
  buildAdminCrownHuntActivatePath,
  buildAdminCrownHuntPausePath,
  DEFAULT_CROWN_HUNT_PAGE_SIZE,
  MAX_CROWN_HUNT_PAGE_SIZE,
  CROWN_HUNT_REPEAT_RULES,
  CROWN_HUNT_POINT_STATUSES,
  MIN_GEOFENCE_RADIUS_METERS,
  MAX_GEOFENCE_RADIUS_METERS,
  MIN_REWARD_POINTS,
  MAX_REWARD_POINTS,
  MAX_TITLE_LENGTH,
  MAX_DESCRIPTION_LENGTH,
  type PaginatedCrownHuntPointsResponse,
  type CrownHuntPointDetailResponse,
  type CrownHuntClaimResponse,
  type PaginatedCrownHuntClaimHistoryResponse,
  type PaginatedAdminCrownHuntPointsResponse,
  type PaginatedAdminCrownHuntClaimsResponse,
} from '@carcommunity/shared/crown-hunt';

import { requireMemberHook, requireAdminHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { CrownHuntService } from '../lib/crown-hunt-service.js';
import { PointsService } from '../lib/points-service.js';
import { DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';

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
          ? Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_CROWN_HUNT_PAGE_SIZE))
          : DEFAULT_CROWN_HUNT_PAGE_SIZE,
      ),
  })
  .strict();

const viewportQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v ? Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_CROWN_HUNT_PAGE_SIZE)) : DEFAULT_CROWN_HUNT_PAGE_SIZE,
    ),
    minLat: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
    maxLat: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
    minLon: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
    maxLon: z.string().optional().transform((v) => (v ? parseFloat(v) : undefined)),
  })
  .strict();

const pointIdParamsSchema = z.object({ pointId: z.string().uuid() }).strict();

const claimBodySchema = z
  .object({
    latitude: z.number().gte(-90).lte(90),
    longitude: z.number().gte(-180).lte(180),
    accuracyMeters: z.number().positive().nullable().optional(),
    speedMetersPerSecond: z.number().min(0).nullable().optional(),
    recordedAt: z.string().datetime({ offset: true }),
    idempotencyKey: z.string().min(1).max(200),
    // Platform integrity placeholder — TODO: populate once native integration exists
    platformIntegrityPassed: z.boolean().nullable().optional(),
  })
  .strict();

const adminCreatePointBodySchema = z
  .object({
    title: z.string().min(1).max(MAX_TITLE_LENGTH),
    description: z.string().max(MAX_DESCRIPTION_LENGTH).nullable().optional(),
    latitude: z.number().gte(-90).lte(90),
    longitude: z.number().gte(-180).lte(180),
    geofenceRadiusMeters: z.number().int().gte(MIN_GEOFENCE_RADIUS_METERS).lte(MAX_GEOFENCE_RADIUS_METERS),
    rewardPoints: z.number().int().gte(MIN_REWARD_POINTS).lte(MAX_REWARD_POINTS),
    repeatRule: z.enum(CROWN_HUNT_REPEAT_RULES),
    availableFrom: z.string().datetime({ offset: true }).nullable().optional(),
    availableUntil: z.string().datetime({ offset: true }).nullable().optional(),
  })
  .strict();

const adminUpdatePointBodySchema = z
  .object({
    title: z.string().min(1).max(MAX_TITLE_LENGTH).optional(),
    description: z.string().max(MAX_DESCRIPTION_LENGTH).nullable().optional(),
    latitude: z.number().gte(-90).lte(90).optional(),
    longitude: z.number().gte(-180).lte(180).optional(),
    geofenceRadiusMeters: z.number().int().gte(MIN_GEOFENCE_RADIUS_METERS).lte(MAX_GEOFENCE_RADIUS_METERS).optional(),
    rewardPoints: z.number().int().gte(MIN_REWARD_POINTS).lte(MAX_REWARD_POINTS).optional(),
    repeatRule: z.enum(CROWN_HUNT_REPEAT_RULES).optional(),
    availableFrom: z.string().datetime({ offset: true }).nullable().optional(),
    availableUntil: z.string().datetime({ offset: true }).nullable().optional(),
  })
  .strict();

const adminActivateBodySchema = z
  .object({
    safeLocationConfirmed: z.boolean().refine((v) => v === true, {
      message: 'Safety confirmation is required.',
    }),
    approvalNote: z.string().min(3).max(500),
  })
  .strict();

const adminPauseBodySchema = z
  .object({
    reason: z.string().max(500).optional(),
  })
  .strict();

const adminListClaimsQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v ? Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_CROWN_HUNT_PAGE_SIZE)) : DEFAULT_CROWN_HUNT_PAGE_SIZE,
    ),
    result: z.string().optional(),
  })
  .strict();

const adminListPointsQuerySchema = z
  .object({
    page: z.string().optional().transform((v) => (v ? Math.max(1, parseInt(v, 10) || 1) : 1)),
    pageSize: z.string().optional().transform((v) =>
      v ? Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, parseInt(v, 10) || DEFAULT_CROWN_HUNT_PAGE_SIZE)) : DEFAULT_CROWN_HUNT_PAGE_SIZE,
    ),
    status: z.enum(CROWN_HUNT_POINT_STATUSES).optional(),
  })
  .strict();

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------

export interface RegisterCrownHuntRoutesDependencies {
  crownHuntService?: CrownHuntService;
}

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export async function registerCrownHuntRoutes(
  app: FastifyInstance,
  dependencies: RegisterCrownHuntRoutesDependencies = {},
): Promise<void> {
  const pointsService = new PointsService(app.prisma);
  const crownHuntService =
    dependencies.crownHuntService ?? new CrownHuntService(app.prisma, pointsService);

  // ---------------------------------------------------------------------------
  // Mobile: GET /v1/crown-hunt/points
  // ---------------------------------------------------------------------------

  /**
   * Returns active, currently available Kronjakt points.
   * Supports optional geographic viewport filtering.
   * Returns each point's claimed-state for the authenticated user.
   * Never returns other users' claims or internal risk metadata.
   */
  app.get(
    CROWN_HUNT_ROUTE_PATHS.points,
    { preHandler: requireMemberHook },
    async (request): Promise<PaginatedCrownHuntPointsResponse> => {
      const auth = request.auth!;
      const query = viewportQuerySchema.parse(request.query);

      const result = await crownHuntService.listActivePoints({
        userId: auth.userId,
        page: query.page,
        pageSize: query.pageSize,
        minLat: query.minLat,
        maxLat: query.maxLat,
        minLon: query.minLon,
        maxLon: query.maxLon,
      });

      return {
        ok: true,
        data: { points: result.points },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // ---------------------------------------------------------------------------
  // Mobile: GET /v1/crown-hunt/points/:pointId
  // ---------------------------------------------------------------------------

  /**
   * Returns detailed information for a single active Kronjakt point.
   * Includes safety instructions and reward amount.
   * Does not return internal risk rules or other users' claim data.
   */
  app.get(
    buildCrownHuntPointPath(':pointId'),
    { preHandler: requireMemberHook },
    async (request): Promise<CrownHuntPointDetailResponse> => {
      const auth = request.auth!;
      const { pointId } = pointIdParamsSchema.parse(request.params);

      const detail = await crownHuntService.getPointDetail(pointId, auth.userId);

      return { ok: true, data: detail };
    },
  );

  // ---------------------------------------------------------------------------
  // Mobile: POST /v1/crown-hunt/points/:pointId/claim
  // ---------------------------------------------------------------------------

  /**
   * Submits a Kronjakt claim for the authenticated user.
   *
   * Safety rules enforced:
   *  - Reward amount must NOT be provided by the client.
   *  - userId is derived from the authenticated session.
   *  - Backend validates all eligibility, geofence, speed, and risk conditions.
   *  - Claims are never automatic.
   *  - Risk metadata is never returned to the client.
   *  - Rate limiting is applied via the global rate-limit plugin.
   */
  app.post(
    buildCrownHuntClaimPath(':pointId'),
    { preHandler: requireMemberHook },
    async (request): Promise<CrownHuntClaimResponse> => {
      const auth = request.auth!;
      const { pointId } = pointIdParamsSchema.parse(request.params);
      const body = claimBodySchema.parse(request.body);

      // Reject any attempt to include a reward amount or user ID in the body.
      // This is enforced by the `.strict()` on claimBodySchema, but we log clearly.
      const rawBody = request.body as Record<string, unknown>;
      if ('rewardPoints' in rawBody || 'userId' in rawBody || 'distance' in rawBody || 'eligible' in rawBody) {
        throw new AppError(400, 'validation_error', 'Invalid fields in claim request.');
      }

      // Resolve crownHunt feature flag from app settings or default.
      // The full feature flag resolver is injected by registerFeatureFlagRoutes,
      // but for the claim we read it from a simple fallback.
      const crownHuntEnabled = await resolveCrownHuntFlag(app);

      const result = await crownHuntService.claimPoint({
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        pointId,
        latitude: body.latitude,
        longitude: body.longitude,
        accuracyMeters: body.accuracyMeters,
        speedMetersPerSecond: body.speedMetersPerSecond,
        recordedAt: body.recordedAt,
        idempotencyKey: body.idempotencyKey,
        platformIntegrityPassed: body.platformIntegrityPassed,
        crownHuntFeatureEnabled: crownHuntEnabled,
      });

      // Never return raw anti-fraud metadata to the client.
      return {
        ok: true,
        data: {
          result: result.result,
          pointsAwarded: result.pointsAwarded,
          newBalance: result.newBalance,
          message: result.message,
        },
      };
    },
  );

  // ---------------------------------------------------------------------------
  // Mobile: GET /v1/crown-hunt/me/claims
  // ---------------------------------------------------------------------------

  /**
   * Returns the current user's paginated Kronjakt claim history.
   * Does not include exact claim coordinates or internal fraud metadata.
   * Returns only the current user's own claims.
   */
  app.get(
    CROWN_HUNT_ROUTE_PATHS.myClaims,
    { preHandler: requireMemberHook },
    async (request): Promise<PaginatedCrownHuntClaimHistoryResponse> => {
      const auth = request.auth!;
      const query = paginationQuerySchema.parse(request.query);

      const result = await crownHuntService.listClaimHistory({
        userId: auth.userId,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: { claims: result.claims },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // ---------------------------------------------------------------------------
  // Admin: GET /v1/admin/crown-hunt/points
  // ---------------------------------------------------------------------------

  /**
   * Lists all Kronjakt points for admin management.
   * Supports optional status filter.
   * Requires admin or owner role.
   */
  app.get(
    CROWN_HUNT_ROUTE_PATHS.adminPoints,
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedAdminCrownHuntPointsResponse> => {
      const query = adminListPointsQuerySchema.parse(request.query);

      const result = await crownHuntService.adminListPoints({
        page: query.page,
        pageSize: query.pageSize,
        status: query.status,
      });

      return {
        ok: true,
        data: { points: result.points },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // ---------------------------------------------------------------------------
  // Admin: POST /v1/admin/crown-hunt/points
  // ---------------------------------------------------------------------------

  /**
   * Creates a new Kronjakt point in draft status.
   * Activation is a separate action requiring safety confirmation.
   * Requires admin or owner role.
   * Writes an audit log entry.
   */
  app.post(
    CROWN_HUNT_ROUTE_PATHS.adminPoints,
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true; data: object }> => {
      const auth = request.auth!;
      const body = adminCreatePointBodySchema.parse(request.body);

      const point = await crownHuntService.adminCreatePoint(auth.userId, {
        title: body.title,
        description: body.description,
        latitude: body.latitude,
        longitude: body.longitude,
        geofenceRadiusMeters: body.geofenceRadiusMeters,
        rewardPoints: body.rewardPoints,
        repeatRule: body.repeatRule,
        availableFrom: body.availableFrom,
        availableUntil: body.availableUntil,
      });

      return { ok: true, data: point };
    },
  );

  // ---------------------------------------------------------------------------
  // Admin: PATCH /v1/admin/crown-hunt/points/:pointId
  // ---------------------------------------------------------------------------

  /**
   * Updates a draft or paused Kronjakt point.
   * Active and ended points cannot be edited.
   * Requires admin or owner role.
   * Writes an audit log entry.
   */
  app.patch(
    buildAdminCrownHuntPointPath(':pointId'),
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true; data: object }> => {
      const auth = request.auth!;
      const { pointId } = pointIdParamsSchema.parse(request.params);
      const body = adminUpdatePointBodySchema.parse(request.body);

      const point = await crownHuntService.adminUpdatePoint(auth.userId, pointId, {
        title: body.title,
        description: body.description,
        latitude: body.latitude,
        longitude: body.longitude,
        geofenceRadiusMeters: body.geofenceRadiusMeters,
        rewardPoints: body.rewardPoints,
        repeatRule: body.repeatRule,
        availableFrom: body.availableFrom,
        availableUntil: body.availableUntil,
      });

      return { ok: true, data: point };
    },
  );

  // ---------------------------------------------------------------------------
  // Admin: POST /v1/admin/crown-hunt/points/:pointId/activate
  // ---------------------------------------------------------------------------

  /**
   * Activates a Kronjakt point.
   * Requires safety confirmation checkbox and an approval note.
   * Only admin or owner roles may activate.
   * Writes an audit log entry.
   */
  app.post(
    buildAdminCrownHuntActivatePath(':pointId'),
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true; data: object }> => {
      const auth = request.auth!;
      const { pointId } = pointIdParamsSchema.parse(request.params);
      const body = adminActivateBodySchema.parse(request.body);

      const point = await crownHuntService.adminActivatePoint(
        auth.userId,
        auth.role,
        pointId,
        { safeLocationConfirmed: body.safeLocationConfirmed === true, approvalNote: body.approvalNote },
      );

      return { ok: true, data: point };
    },
  );

  // ---------------------------------------------------------------------------
  // Admin: POST /v1/admin/crown-hunt/points/:pointId/pause
  // ---------------------------------------------------------------------------

  /**
   * Pauses an active Kronjakt point.
   * Active or draft points may be paused.
   * Requires admin or owner role.
   * Writes an audit log entry.
   */
  app.post(
    buildAdminCrownHuntPausePath(':pointId'),
    { preHandler: requireAdminHook },
    async (request): Promise<{ ok: true; data: object }> => {
      const auth = request.auth!;
      const { pointId } = pointIdParamsSchema.parse(request.params);
      const body = adminPauseBodySchema.parse(request.body);

      const point = await crownHuntService.adminPausePoint(
        auth.userId,
        auth.role,
        pointId,
        body.reason ?? '',
      );

      return { ok: true, data: point };
    },
  );

  // ---------------------------------------------------------------------------
  // Admin: GET /v1/admin/crown-hunt/claims
  // ---------------------------------------------------------------------------

  /**
   * Lists all Kronjakt claims for admin review.
   * Supports optional result filter (e.g. risk_review).
   * Does not expose exact claim coordinates or anti-fraud thresholds.
   * Requires admin or owner role.
   */
  app.get(
    CROWN_HUNT_ROUTE_PATHS.adminClaims,
    { preHandler: requireAdminHook },
    async (request): Promise<PaginatedAdminCrownHuntClaimsResponse> => {
      const query = adminListClaimsQuerySchema.parse(request.query);

      const result = await crownHuntService.adminListClaims({
        page: query.page,
        pageSize: query.pageSize,
        result: query.result,
      });

      return {
        ok: true,
        data: { claims: result.claims },
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

// ---------------------------------------------------------------------------
// Feature flag resolver
// ---------------------------------------------------------------------------

/**
 * Reads the crownHunt feature flag from the database or falls back to defaults.
 * The full FeatureFlag model is organisation-scoped; for MVP we fall back to
 * the static DEFAULT_FEATURE_FLAGS value when no DB row exists.
 */
async function resolveCrownHuntFlag(app: FastifyInstance): Promise<boolean> {
  try {
    const row = await app.prisma.featureFlag.findFirst({
      where: { key: 'crownHunt' },
      select: { enabled: true },
    });
    return row ? row.enabled : DEFAULT_FEATURE_FLAGS.crownHunt;
  } catch {
    return DEFAULT_FEATURE_FLAGS.crownHunt;
  }
}
