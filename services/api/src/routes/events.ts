import {
  EVENT_ROUTE_PATHS,
  EVENT_RSVP_STATUSES,
  buildEventDetailPath,
  buildEventRsvpPath,
  canAccessEventAdmin,
  canRsvpToEvent,
  canViewEventDetails,
  canViewEventTeaser,
  type AdminEventsResponse,
  type EventDetailResponse,
  type EventRsvpResponse,
  type EventTeasersResponse,
} from '@carcommunity/shared/events';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { requireAdminHook, requireAuthHook, requireMemberHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { EventService } from '../lib/event-service.js';

const eventIdParamsSchema = z
  .object({
    eventId: z.string().uuid(),
  })
  .strict();

const eventRsvpBodySchema = z
  .object({
    status: z.enum(EVENT_RSVP_STATUSES),
  })
  .strict();

export interface RegisterEventRoutesDependencies {
  eventService?: EventService;
}

export async function registerEventRoutes(
  app: FastifyInstance,
  dependencies: RegisterEventRoutesDependencies = {},
): Promise<void> {
  const eventService = dependencies.eventService ?? new EventService(app.prisma);

  /**
   * GET /v1/events/teasers
   * Available to all authenticated, non-suspended, non-deleted users.
   * Returns only teaser-safe fields (no exact location).
   */
  app.get(
    EVENT_ROUTE_PATHS.teasers,
    { preHandler: requireAuthHook },
    async (request): Promise<EventTeasersResponse> => {
      const auth = request.auth!;

      if (!canViewEventTeaser(auth)) {
        throw new AppError(403, 'forbidden', 'Access denied.');
      }

      const result = await eventService.getEventTeasers();

      return {
        ok: true,
        data: { events: result.events },
        meta: { total: result.total },
      };
    },
  );

  /**
   * GET /v1/events/:eventId
   * Requires authenticated user with active member_monthly subscription.
   * Suspended and deleted users are blocked.
   */
  app.get(
    buildEventDetailPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<EventDetailResponse> => {
      const auth = request.auth!;

      if (!canViewEventDetails(auth)) {
        throw new AppError(403, 'forbidden', 'Member subscription required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const result = await eventService.getEventDetail({
        eventId: params.eventId,
        viewerUserId: auth.userId,
      });

      return {
        ok: true,
        data: { event: result.event },
      };
    },
  );

  /**
   * POST /v1/events/:eventId/rsvp
   * Requires authenticated user with active member_monthly subscription.
   * Suspended and deleted users are blocked.
   * Upserts RSVP for the current user.
   */
  app.post(
    buildEventRsvpPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<EventRsvpResponse> => {
      const auth = request.auth!;

      if (!canRsvpToEvent(auth)) {
        throw new AppError(403, 'forbidden', 'Member subscription required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const body = eventRsvpBodySchema.parse(request.body);

      const result = await eventService.upsertRsvp({
        eventId: params.eventId,
        userId: auth.userId,
        status: body.status,
      });

      return {
        ok: true,
        data: { rsvp: result },
      };
    },
  );

  /**
   * GET /v1/admin/events
   * Requires admin or owner role.
   * Returns operational admin summary only.
   *
   * TODO: Dangerous admin actions (create, edit, cancel) require reason and audit logging.
   *   Do not add destructive actions here until audit logging is wired in.
   */
  app.get(
    EVENT_ROUTE_PATHS.adminEvents,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventsResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const result = await eventService.getAdminEvents();

      return {
        ok: true,
        data: { events: result.events },
        meta: { total: result.total },
      };
    },
  );
}
