/**
 * Saved drives API routes.
 *
 * Routes:
 *  GET  /v1/live-location/sessions/:sessionId/post-drive-summary
 *  POST /v1/live-location/sessions/:sessionId/save-drive
 *  POST /v1/live-location/sessions/:sessionId/discard-drive
 *  GET  /v1/saved-drives
 *  GET  /v1/saved-drives/:driveId
 *  DELETE /v1/saved-drives/:driveId
 *
 * Access control:
 *  - All routes require authentication.
 *  - Ownership is always verified by the service layer, never the client.
 *  - member_monthly is required for save and detail route overview.
 *  - Suspended/deleted users are rejected.
 *
 * Privacy:
 *  - Raw temporary route points are never returned.
 *  - No top-speed, speed rankings, or exact addresses.
 */

import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  DEFAULT_SAVED_DRIVES_PAGE_SIZE,
  MAX_SAVED_DRIVES_PAGE_SIZE,
  SAVED_DRIVES_ROUTE_PATHS,
  type DeleteSavedDriveResponse,
  type DiscardDriveResponse,
  type PaginatedSavedDrivesResponse,
  type PostDriveSummaryResponse,
  type SaveDriveResponse,
  type SavedDriveDetail,
  type SavedDriveDetailResponse,
} from '@carcommunity/shared/saved-drives';

import { requireAuthHook } from '../lib/auth-context.js';
import { SavedDriveService } from '../lib/saved-drive-service.js';

const sessionParamsSchema = z.object({ sessionId: z.string().uuid() }).strict();
const driveParamsSchema = z.object({ driveId: z.string().uuid() }).strict();

const listQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).default(1),
    pageSize: z
      .coerce
      .number()
      .int()
      .min(1)
      .max(MAX_SAVED_DRIVES_PAGE_SIZE)
      .default(DEFAULT_SAVED_DRIVES_PAGE_SIZE),
  })
  .strict();

/**
 * Picks only the fields defined in SavedDriveDetail to ensure no extra fields
 * (such as a hypothetical topSpeed) can leak from the service layer into the
 * HTTP response. Defense-in-depth: TypeScript types guard at compile time,
 * this pick guards at runtime.
 */
function toSafeDriveDetail(drive: SavedDriveDetail): SavedDriveDetail {
  return {
    id: drive.id,
    startedAt: drive.startedAt,
    endedAt: drive.endedAt,
    durationSeconds: drive.durationSeconds,
    distanceMeters: drive.distanceMeters,
    averageSpeedMetersPerSecond: drive.averageSpeedMetersPerSecond,
    approximateStartArea: drive.approximateStartArea,
    approximateEndArea: drive.approximateEndArea,
    routeOverview: drive.routeOverview,
    createdAt: drive.createdAt,
  };
}

export interface RegisterSavedDrivesRoutesDependencies {
  savedDriveService?: SavedDriveService;
}

export async function registerSavedDrivesRoutes(
  app: FastifyInstance,
  dependencies: RegisterSavedDrivesRoutesDependencies = {},
): Promise<void> {
  const savedDriveService = dependencies.savedDriveService ?? new SavedDriveService(app.prisma);

  // GET /v1/live-location/sessions/:sessionId/post-drive-summary
  app.get(
    SAVED_DRIVES_ROUTE_PATHS.postDriveSummary(':sessionId'),
    { preHandler: requireAuthHook },
    async (request): Promise<PostDriveSummaryResponse> => {
      const auth = request.auth!;
      const params = sessionParamsSchema.parse(request.params);

      const result = await savedDriveService.getPostDriveSummary({
        sessionId: params.sessionId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: {
          summary: result.summary,
          canSave: result.canSave,
        },
      };
    },
  );

  // POST /v1/live-location/sessions/:sessionId/save-drive
  app.post(
    SAVED_DRIVES_ROUTE_PATHS.saveDrive(':sessionId'),
    { preHandler: requireAuthHook },
    async (request): Promise<SaveDriveResponse> => {
      const auth = request.auth!;
      const params = sessionParamsSchema.parse(request.params);

      const drive = await savedDriveService.saveDrive({
        sessionId: params.sessionId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: { drive: toSafeDriveDetail(drive) },
      };
    },
  );

  // POST /v1/live-location/sessions/:sessionId/discard-drive
  app.post(
    SAVED_DRIVES_ROUTE_PATHS.discardDrive(':sessionId'),
    { preHandler: requireAuthHook },
    async (request): Promise<DiscardDriveResponse> => {
      const auth = request.auth!;
      const params = sessionParamsSchema.parse(request.params);

      await savedDriveService.discardDrive({
        sessionId: params.sessionId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: { discarded: true, sessionId: params.sessionId },
      };
    },
  );

  // GET /v1/saved-drives
  app.get(
    SAVED_DRIVES_ROUTE_PATHS.list,
    { preHandler: requireAuthHook },
    async (request): Promise<PaginatedSavedDrivesResponse> => {
      const auth = request.auth!;
      const query = listQuerySchema.parse(request.query);

      const result = await savedDriveService.listDrives({
        actor: {
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
        data: { drives: result.drives },
        meta: {
          page: query.page,
          pageSize: query.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // GET /v1/saved-drives/:driveId
  app.get(
    `${SAVED_DRIVES_ROUTE_PATHS.list}/:driveId`,
    { preHandler: requireAuthHook },
    async (request): Promise<SavedDriveDetailResponse> => {
      const auth = request.auth!;
      const params = driveParamsSchema.parse(request.params);

      const drive = await savedDriveService.getDrive({
        driveId: params.driveId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: { drive: toSafeDriveDetail(drive) },
      };
    },
  );

  // DELETE /v1/saved-drives/:driveId
  app.delete(
    `${SAVED_DRIVES_ROUTE_PATHS.list}/:driveId`,
    { preHandler: requireAuthHook },
    async (request): Promise<DeleteSavedDriveResponse> => {
      const auth = request.auth!;
      const params = driveParamsSchema.parse(request.params);

      await savedDriveService.deleteDrive({
        driveId: params.driveId,
        actor: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: { deleted: true, driveId: params.driveId },
      };
    },
  );
}
