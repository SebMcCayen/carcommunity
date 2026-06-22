import {
  GROUP_DRIVE_UPDATABLE_STATUSES,
  buildAdminGroupDriveSummaryPath,
  buildGroupDriveJoinPath,
  buildGroupDriveLeavePath,
  buildGroupDriveMarkersPath,
  buildGroupDriveSummaryPath,
  buildGroupDriveStatusPath,
  type AdminGroupDriveSummaryResponse,
  type GroupDriveMarkersResponse,
  type GroupDriveSummaryResponse,
  type JoinGroupDriveResponse,
  type LeaveGroupDriveResponse,
  type UpdateGroupDriveStatusResponse,
} from '@carcommunity/shared/group-drive';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { requireAdminHook, requireMemberHook } from '../lib/auth-context.js';
import { BlockingService } from '../lib/blocking-service.js';
import { GroupDriveService } from '../lib/group-drive-service.js';

const eventIdParamsSchema = z
  .object({
    eventId: z.string().uuid(),
  })
  .strict();

const updateStatusBodySchema = z
  .object({
    status: z.enum(GROUP_DRIVE_UPDATABLE_STATUSES),
  })
  .strict();

export interface RegisterGroupDriveRoutesDependencies {
  groupDriveService?: GroupDriveService;
  blockingService?: BlockingService;
}

export async function registerGroupDriveRoutes(
  app: FastifyInstance,
  dependencies: RegisterGroupDriveRoutesDependencies = {},
): Promise<void> {
  const groupDriveService = dependencies.groupDriveService ?? new GroupDriveService(app.prisma);
  const blockingService = dependencies.blockingService ?? new BlockingService(app.prisma);

  /**
   * POST /v1/events/:eventId/group-drive/join
   *
   * Join an event group drive.
   * Requires authenticated active member with RSVP going/maybe.
   * Idempotent for active participants; rejoins if previously left.
   * Does NOT start live location automatically.
   */
  app.post(
    buildGroupDriveJoinPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<JoinGroupDriveResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);

      const result = await groupDriveService.joinGroupDrive({
        eventId: params.eventId,
        viewer: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  /**
   * POST /v1/events/:eventId/group-drive/leave
   *
   * Leave an event group drive.
   * Requires authenticated active member. Idempotent.
   * Does NOT stop the user's live location session.
   */
  app.post(
    buildGroupDriveLeavePath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<LeaveGroupDriveResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);

      const result = await groupDriveService.leaveGroupDrive({
        eventId: params.eventId,
        viewer: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  /**
   * PATCH /v1/events/:eventId/group-drive/status
   *
   * Update the current user's group drive participant status.
   * Accepted values: joined, on_the_way, arrived.
   * Use the leave endpoint to set status to `left`.
   */
  app.patch(
    buildGroupDriveStatusPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<UpdateGroupDriveStatusResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);
      const body = updateStatusBodySchema.parse(request.body);

      const result = await groupDriveService.updateStatus({
        eventId: params.eventId,
        viewer: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        status: body.status,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  /**
   * GET /v1/events/:eventId/group-drive
   *
   * Get group drive summary: aggregate counts and safe participant list.
   * Requires authenticated active member.
   * Blocking is enforced in both directions.
   */
  app.get(
    buildGroupDriveSummaryPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<GroupDriveSummaryResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);

      // Fetch user IDs invisible to the viewer due to blocking (both directions).
      const excludeUserIds = await blockingService.getInvisibleUserIds(auth.userId);

      const result = await groupDriveService.getGroupDriveSummary({
        eventId: params.eventId,
        viewer: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        excludeUserIds,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  /**
   * GET /v1/events/:eventId/group-drive/markers
   *
   * Get live location markers for active group drive participants.
   * Requires authenticated active member who is an active group participant.
   * Only participants with active, non-expired, non-stale positions are returned.
   * Blocking is enforced in both directions.
   * The viewer's own marker is excluded.
   */
  app.get(
    buildGroupDriveMarkersPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<GroupDriveMarkersResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);

      // Fetch user IDs invisible to the viewer due to blocking (both directions).
      const excludeUserIds = await blockingService.getInvisibleUserIds(auth.userId);

      const result = await groupDriveService.getGroupDriveMarkers({
        eventId: params.eventId,
        viewer: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        excludeUserIds,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );

  /**
   * GET /v1/admin/events/:eventId/group-drive/summary
   *
   * Admin-only endpoint: aggregate group drive counts for an event.
   * Returns counts only — no individual participant details or positions.
   * Requires admin role.
   */
  app.get(
    buildAdminGroupDriveSummaryPath(':eventId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminGroupDriveSummaryResponse> => {
      const params = eventIdParamsSchema.parse(request.params);

      const result = await groupDriveService.getAdminGroupDriveSummary({
        eventId: params.eventId,
      });

      return {
        ok: true,
        data: result,
      };
    },
  );
}
