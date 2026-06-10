import { DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';
import {
  DEFAULT_LIVE_LOCATION_PAGE_SIZE,
  LIVE_LOCATION_DURATIONS,
  LIVE_LOCATION_ROUTE_PATHS,
  LIVE_LOCATION_STOP_REASONS,
  MAX_LIVE_LOCATION_PAGE_SIZE,
  buildLiveLocationPositionPath,
  buildLiveLocationStopPath,
  type AdminLiveLocationSummaryResponse,
  type HideMeNowResponse,
  type LiveLocationPositionUpdateResponse,
  type LiveLocationStartResponse,
  type LiveLocationStopResponse,
  type PublicLiveLocationMarkerResponse,
} from '@carcommunity/shared/live-location';
import {
  canAccessLiveLocationAdminSummary,
  canShareOwnLiveLocation,
  canViewOtherLiveLocations,
} from '@carcommunity/shared/users';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { requireAuthHook, requireAuthenticatedHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import {
  LIVE_LOCATION_DATABASE_META,
  LiveLocationService,
} from '../lib/live-location-service.js';

const liveLocationSessionParamsSchema = z
  .object({
    sessionId: z.string().uuid(),
  })
  .strict();

const liveLocationCoordinateSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    accuracyMeters: z.number().nonnegative().optional(),
    headingDegrees: z.number().min(0).max(360).optional(),
    speedMetersPerSecond: z.number().nonnegative().optional(),
    recordedAt: z.string().datetime({ offset: true }),
  })
  .strict();

const liveLocationStartRequestSchema = z
  .object({
    duration: z.enum(LIVE_LOCATION_DURATIONS),
  })
  .strict();

const liveLocationUpdateRequestSchema = z
  .object({
    coordinate: liveLocationCoordinateSchema,
  })
  .strict();

const liveLocationStopRequestSchema = z
  .object({
    reason: z.enum(LIVE_LOCATION_STOP_REASONS).optional(),
  })
  .strict();

const liveLocationListQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).default(1),
    pageSize: z.coerce.number().int().min(1).max(MAX_LIVE_LOCATION_PAGE_SIZE).default(DEFAULT_LIVE_LOCATION_PAGE_SIZE),
  })
  .strict();

export interface RegisterLiveLocationRoutesDependencies {
  liveLocationService?: LiveLocationService;
  liveLocationFeatureEnabled?: boolean;
}

export async function registerLiveLocationRoutes(
  app: FastifyInstance,
  dependencies: RegisterLiveLocationRoutesDependencies = {},
): Promise<void> {
  const liveLocationService = dependencies.liveLocationService ?? new LiveLocationService(app.prisma);
  const liveLocationFeatureEnabled = dependencies.liveLocationFeatureEnabled ?? DEFAULT_FEATURE_FLAGS.liveLocation;

  function assertLiveLocationEnabled(): void {
    // TODO: Replace this injected/static fallback with admin-managed database-backed flag evaluation.
    if (!liveLocationFeatureEnabled) {
      throw new AppError(403, 'feature_disabled', 'Live location feature is disabled.');
    }
  }

  app.post(
    LIVE_LOCATION_ROUTE_PATHS.sessions,
    { preHandler: requireAuthHook },
    async (request): Promise<LiveLocationStartResponse> => {
      const auth = request.auth;
      if (!auth) {
        throw new AppError(401, 'unauthenticated', 'Authentication required.');
      }

      if (!canShareOwnLiveLocation(auth)) {
        throw new AppError(403, 'forbidden', 'Your account status prevents live location sharing.');
      }

      assertLiveLocationEnabled();

      const body = liveLocationStartRequestSchema.parse(request.body);
      const result = await liveLocationService.startSession({
        userId: auth.userId,
        duration: body.duration,
      });

      return {
        ok: true,
        data: result,
        meta: LIVE_LOCATION_DATABASE_META,
      };
    },
  );

  app.post(
    buildLiveLocationPositionPath(':sessionId'),
    { preHandler: requireAuthHook },
    async (request): Promise<LiveLocationPositionUpdateResponse> => {
      const auth = request.auth!;

      if (!canShareOwnLiveLocation(auth)) {
        throw new AppError(403, 'forbidden', 'Your account status prevents live location sharing.');
      }

      assertLiveLocationEnabled();

      const params = liveLocationSessionParamsSchema.parse(request.params);
      const body = liveLocationUpdateRequestSchema.parse(request.body);

      const result = await liveLocationService.updateLatestPosition({
        sessionId: params.sessionId,
        userId: auth.userId,
        coordinate: body.coordinate,
      });

      return {
        ok: true,
        data: result,
        meta: LIVE_LOCATION_DATABASE_META,
      };
    },
  );

  app.post(
    buildLiveLocationStopPath(':sessionId'),
    { preHandler: requireAuthenticatedHook },
    async (request): Promise<LiveLocationStopResponse> => {
      const auth = request.auth!;

      const params = liveLocationSessionParamsSchema.parse(request.params);
      liveLocationStopRequestSchema.parse(request.body ?? {});

      const result = await liveLocationService.stopSession({
        sessionId: params.sessionId,
        userId: auth.userId,
      });

      return {
        ok: true,
        data: result,
        meta: LIVE_LOCATION_DATABASE_META,
      };
    },
  );

  app.post(
    LIVE_LOCATION_ROUTE_PATHS.hideMeNow,
    { preHandler: requireAuthenticatedHook },
    async (request): Promise<HideMeNowResponse> => {
      const auth = request.auth!;

      const result = await liveLocationService.hideMeNow({
        userId: auth.userId,
      });

      return {
        ok: true,
        data: result,
        meta: LIVE_LOCATION_DATABASE_META,
      };
    },
  );

  app.get(
    LIVE_LOCATION_ROUTE_PATHS.markers,
    { preHandler: requireAuthHook },
    async (request): Promise<PublicLiveLocationMarkerResponse> => {
      const auth = request.auth;
      if (!auth) {
        throw new AppError(401, 'unauthenticated', 'Authentication required.');
      }

      if (!canViewOtherLiveLocations(auth)) {
        throw new AppError(403, 'forbidden', 'Member subscription required.');
      }

      assertLiveLocationEnabled();

      // TODO: Enforce blocking visibility filtering once user blocking relationships are persisted.
      //   Expected behavior: if A blocks B or B blocks A, neither user should receive the other's marker.
      const query = liveLocationListQuerySchema.parse(request.query);
      const result = await liveLocationService.getVisibleMarkers({
        viewer: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: {
          markers: result.markers,
          generatedAt: result.generatedAt,
        },
        meta: {
          ...LIVE_LOCATION_DATABASE_META,
          page: query.page,
          pageSize: query.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  app.get(
    LIVE_LOCATION_ROUTE_PATHS.adminSummary,
    { preHandler: requireAuthHook },
    async (request): Promise<AdminLiveLocationSummaryResponse> => {
      const auth = request.auth!;

      if (!canAccessLiveLocationAdminSummary(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const summary = await liveLocationService.getAdminSummary();

      return {
        ok: true,
        data: summary,
        meta: LIVE_LOCATION_DATABASE_META,
      };
    },
  );
}
