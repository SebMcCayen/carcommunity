import { randomUUID } from 'node:crypto';
import {
  DEFAULT_FEATURE_FLAGS,
} from '@carcommunity/shared/feature-flags';
import {
  DEFAULT_LIVE_LOCATION_PAGE_SIZE,
  LIVE_LOCATION_DURATIONS,
  LIVE_LOCATION_ROUTE_PATHS,
  LIVE_LOCATION_STOP_REASONS,
  LIVE_LOCATION_TTL_MINUTES_MAX,
  MAX_LIVE_LOCATION_PAGE_SIZE,
  buildLiveLocationPositionPath,
  buildLiveLocationStopPath,
  calculateLiveLocationExpiresAt,
  type AdminLiveLocationSummaryResponse,
  type HideMeNowResponse,
  type LiveLocationOwnSessionResponse,
  type LiveLocationSessionSummary,
  type PublicLiveLocationMarkerResponse,
} from '@carcommunity/shared/live-location';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

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

const PLACEHOLDER_META = {
  source: 'placeholder',
  productionReady: false,
  ttlCleanupPrepared: true,
} as const;

function createSessionSummary(input: {
  id?: string;
  duration: (typeof LIVE_LOCATION_DURATIONS)[number];
  status: LiveLocationSessionSummary['status'];
  startedAt: string;
  stoppedAt?: string | null;
}): LiveLocationSessionSummary {
  return {
    id: input.id ?? randomUUID(),
    status: input.status,
    duration: input.duration,
    startedAt: input.startedAt,
    expiresAt: calculateLiveLocationExpiresAt(input.startedAt, input.duration),
    stoppedAt: input.stoppedAt ?? null,
  };
}

function createOwnSessionResponse(input: {
  session: LiveLocationSessionSummary;
  latestPosition: LiveLocationOwnSessionResponse['data']['latestPosition'];
  latestPositionRemoved: boolean;
}): LiveLocationOwnSessionResponse {
  return {
    ok: true,
    data: {
      session: input.session,
      latestPosition: input.latestPosition,
      latestPositionRemoved: input.latestPositionRemoved,
    },
    meta: PLACEHOLDER_META,
  };
}

export async function registerLiveLocationRoutes(app: FastifyInstance): Promise<void> {
  app.post(LIVE_LOCATION_ROUTE_PATHS.sessions, async (request): Promise<LiveLocationOwnSessionResponse> => {
    const body = liveLocationStartRequestSchema.parse(request.body);
    const startedAt = new Date().toISOString();

    // TODO: Require authenticated user context from the backend session/token.
    // TODO: Evaluate backend feature flag `liveLocation` before allowing session start.
    // TODO: Enforce suspension checks before allowing a user to share live location.
    // TODO: Persist LiveLocationSession in PostgreSQL and reuse an existing active session safely.
    // TODO: Prepare TTL cleanup workers that purge stale latest-position records at or before LIVE_LOCATION_TTL_MINUTES_MAX.
    return createOwnSessionResponse({
      session: createSessionSummary({
        duration: body.duration,
        status: 'active',
        startedAt,
      }),
      latestPosition: null,
      latestPositionRemoved: false,
    });
  });

  app.post(
    buildLiveLocationPositionPath(':sessionId'),
    async (request): Promise<LiveLocationOwnSessionResponse> => {
      const params = liveLocationSessionParamsSchema.parse(request.params);
      const body = liveLocationUpdateRequestSchema.parse(request.body);

      // TODO: Require authenticated user context and ensure the session belongs to the caller.
      // TODO: Evaluate backend feature flag `liveLocation` before accepting updates.
      // TODO: Enforce blocking checks before fan-out and future visibility reads.
      // TODO: Enforce foreground-only/manual opt-in rules before enabling any background tracking.
      // TODO: Upsert the latest backend position only; never store route history or passive tracking data.
      // TODO: Apply TTL cleanup using LIVE_LOCATION_TTL_MINUTES_MAX for stale latest-position records.
      return createOwnSessionResponse({
        session: createSessionSummary({
          id: params.sessionId,
          duration: '1h',
          status: 'active',
          startedAt: body.coordinate.recordedAt,
        }),
        latestPosition: body.coordinate,
        latestPositionRemoved: false,
      });
    },
  );

  app.post(
    buildLiveLocationStopPath(':sessionId'),
    async (request): Promise<LiveLocationOwnSessionResponse> => {
      const params = liveLocationSessionParamsSchema.parse(request.params);
      liveLocationStopRequestSchema.parse(request.body ?? {});
      const stoppedAt = new Date().toISOString();

      // TODO: Require authenticated user context and ensure the session belongs to the caller.
      // TODO: Evaluate backend feature flag `liveLocation` before serving mutable session actions.
      // TODO: Persist stopped status and remove the latest backend position when stop/hide semantics require it.
      // TODO: Enforce audit logging for future admin-triggered stop actions.
      return createOwnSessionResponse({
        session: createSessionSummary({
          id: params.sessionId,
          duration: '1h',
          status: 'stopped',
          startedAt: stoppedAt,
          stoppedAt,
        }),
        latestPosition: null,
        latestPositionRemoved: true,
      });
    },
  );

  app.post(LIVE_LOCATION_ROUTE_PATHS.hideMeNow, async (): Promise<HideMeNowResponse> => {
    const stoppedAt = new Date().toISOString();

    // TODO: Require authenticated user context and resolve the caller's active live location session.
    // TODO: Evaluate backend feature flag `liveLocation` before serving hide-now.
    // TODO: Remove the latest backend position immediately and mark the active session as stopped.
    // TODO: Apply TTL cleanup so stale caches/derived views never keep hidden location data.
    return createOwnSessionResponse({
      session: createSessionSummary({
        duration: '1h',
        status: 'stopped',
        startedAt: stoppedAt,
        stoppedAt,
      }),
      latestPosition: null,
      latestPositionRemoved: true,
    });
  });

  app.get(LIVE_LOCATION_ROUTE_PATHS.markers, async (request): Promise<PublicLiveLocationMarkerResponse> => {
    const query = liveLocationListQuerySchema.parse(request.query);

    // TODO: Require authenticated user context before exposing any cross-user live location data.
    // TODO: Enforce backend visibility rules: member_monthly required, admin/owner bypass, suspension override.
    // TODO: Enforce blocking once the blocking graph is available.
    // TODO: Evaluate backend feature flag `liveLocation` before returning any marker data.
    // Safe default: return no exact coordinates until auth, entitlement, and blocking checks are implemented.
    return {
      ok: true,
      data: {
        markers: [],
        generatedAt: new Date().toISOString(),
      },
      meta: {
        ...PLACEHOLDER_META,
        page: query.page,
        pageSize: query.pageSize,
        total: 0,
        hasNext: false,
      },
    };
  });

  app.get(
    LIVE_LOCATION_ROUTE_PATHS.adminSummary,
    async (request): Promise<AdminLiveLocationSummaryResponse> => {
      const query = liveLocationListQuerySchema.parse(request.query);

      // TODO: Require backend-verified admin/owner authorization for all admin live location views.
      // TODO: Evaluate backend feature flag `liveLocation` before exposing operational controls.
      // TODO: Add audit logging for future admin support actions such as hide/stop.
      // TODO: Keep this view moderation/operations-only and never expose exact coordinates or route history.
      return {
        ok: true,
        data: {
          activeSessionCount: 0,
          expiredSessionCount: 0,
          operationalStatus: 'placeholder_safe_default',
          featureFlagKey: 'liveLocation',
          featureFlagEnabled: DEFAULT_FEATURE_FLAGS.liveLocation,
          latestPositionTtlMinutesMax: LIVE_LOCATION_TTL_MINUTES_MAX,
          sessions: [],
        },
        meta: {
          ...PLACEHOLDER_META,
          page: query.page,
          pageSize: query.pageSize,
          total: 0,
          hasNext: false,
        },
      };
    },
  );
}
