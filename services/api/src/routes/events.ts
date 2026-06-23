import {
  EVENT_ROUTE_PATHS,
  EVENT_RSVP_STATUSES,
  EVENT_STATUSES,
  buildAdminEventCancelPath,
  buildAdminEventCompletePath,
  buildAdminEventPath,
  buildAdminEventPublishPath,
  buildEventDetailPath,
  buildEventRsvpPath,
  canAccessEventAdmin,
  canRsvpToEvent,
  canViewEventDetails,
  canViewEventTeaser,
  type AdminEventResponse,
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
import type { BadgeService } from '../lib/badge-service.js';

const eventTeasersQuerySchema = z
  .object({
    cursor: z.string().uuid().optional(),
    take: z.coerce.number().int().min(1).max(100).optional(),
  })
  .strict();

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

const adminEventsQuerySchema = z
  .object({
    page: z.coerce.number().int().min(1).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional(),
    status: z.enum(EVENT_STATUSES).optional(),
    upcoming: z.enum(['true', 'false']).transform((v) => v === 'true').optional(),
    isOfficial: z.enum(['true', 'false']).transform((v) => v === 'true').optional(),
  })
  .strict();

const createEventBodySchema = z
  .object({
    title: z.string().min(1).max(200),
    summary: z.string().max(2000).nullable().optional(),
    description: z.string().max(10000).nullable().optional(),
    startsAt: z.string().datetime(),
    endsAt: z.string().datetime().nullable().optional(),
    approximateArea: z.string().min(1).max(200),
    locationName: z.string().max(200).nullable().optional(),
    address: z.string().max(400).nullable().optional(),
    latitude: z.number().min(-90).max(90).nullable().optional(),
    longitude: z.number().min(-180).max(180).nullable().optional(),
    isOfficial: z.boolean().optional(),
  })
  .strict();

const updateEventBodySchema = z
  .object({
    title: z.string().min(1).max(200).optional(),
    summary: z.string().max(2000).nullable().optional(),
    description: z.string().max(10000).nullable().optional(),
    startsAt: z.string().datetime().optional(),
    endsAt: z.string().datetime().nullable().optional(),
    approximateArea: z.string().min(1).max(200).optional(),
    locationName: z.string().max(200).nullable().optional(),
    address: z.string().max(400).nullable().optional(),
    latitude: z.number().min(-90).max(90).nullable().optional(),
    longitude: z.number().min(-180).max(180).nullable().optional(),
    isOfficial: z.boolean().optional(),
  })
  .strict();

const cancelEventBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
  })
  .strict();

export interface RegisterEventRoutesDependencies {
  eventService?: EventService;
  badgeService?: BadgeService;
}

export async function registerEventRoutes(
  app: FastifyInstance,
  dependencies: RegisterEventRoutesDependencies = {},
): Promise<void> {
  const eventService = dependencies.eventService ?? new EventService(app.prisma);
  const badgeService = dependencies.badgeService;

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

      const query = eventTeasersQuerySchema.parse(request.query);
      const result = await eventService.getEventTeasers({ cursor: query.cursor, take: query.take });

      return {
        ok: true,
        data: { events: result.events },
        meta: { total: result.total, nextCursor: result.nextCursor },
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
   * Returns operational admin summary with optional filters and pagination.
   */
  app.get(
    EVENT_ROUTE_PATHS.adminEvents,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventsResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const query = adminEventsQuerySchema.parse(request.query);
      const result = await eventService.getAdminEvents({
        page: query.page,
        pageSize: query.pageSize,
        status: query.status,
        upcoming: query.upcoming,
        isOfficial: query.isOfficial,
      });

      return {
        ok: true,
        data: { events: result.events },
        meta: { total: result.total, page: result.page, pageSize: result.pageSize },
      };
    },
  );

  /**
   * GET /v1/admin/events/:eventId
   * Requires admin or owner role.
   * Returns full event detail including exact location.
   */
  app.get(
    buildAdminEventPath(':eventId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const result = await eventService.getAdminEvent(params.eventId);

      return { ok: true, data: { event: result.event } };
    },
  );

  /**
   * POST /v1/admin/events
   * Requires admin or owner role.
   * Creates a new event as draft. Status and creator cannot be overridden.
   */
  app.post(
    EVENT_ROUTE_PATHS.adminEvents,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const body = createEventBodySchema.parse(request.body);
      const result = await eventService.createEvent({
        actorUserId: auth.userId,
        data: body,
      });

      return { ok: true, data: { event: result.event } };
    },
  );

  /**
   * PATCH /v1/admin/events/:eventId
   * Requires admin or owner role.
   * Updates draft or published events. Status changes are not permitted here.
   */
  app.patch(
    buildAdminEventPath(':eventId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const body = updateEventBodySchema.parse(request.body);
      const result = await eventService.updateEvent({
        actorUserId: auth.userId,
        eventId: params.eventId,
        data: body,
      });

      return { ok: true, data: { event: result.event } };
    },
  );

  /**
   * POST /v1/admin/events/:eventId/publish
   * Requires admin or owner role.
   * Publishes a valid draft event after checking required fields and future start time.
   */
  app.post(
    buildAdminEventPublishPath(':eventId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const result = await eventService.publishEvent({
        actorUserId: auth.userId,
        eventId: params.eventId,
      });

      return { ok: true, data: { event: result.event } };
    },
  );

  /**
   * POST /v1/admin/events/:eventId/cancel
   * Requires admin or owner role.
   * Cancels a published or draft event. Requires a reason. Does not hard-delete.
   */
  app.post(
    buildAdminEventCancelPath(':eventId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const body = cancelEventBodySchema.parse(request.body);
      const result = await eventService.cancelEvent({
        actorUserId: auth.userId,
        eventId: params.eventId,
        reason: body.reason,
      });

      return { ok: true, data: { event: result.event } };
    },
  );

  /**
   * POST /v1/admin/events/:eventId/complete
   * Requires admin or owner role.
   * Marks a published event as completed.
   * Triggers event badge evaluation (first_event, five_events) for attendees
   * with a 'going' RSVP as a conservative attendance proxy.
   */
  app.post(
    buildAdminEventCompletePath(':eventId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventResponse> => {
      const auth = request.auth!;

      if (!canAccessEventAdmin(auth)) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = eventIdParamsSchema.parse(request.params);
      const result = await eventService.completeEvent({
        actorUserId: auth.userId,
        eventId: params.eventId,
      });

      // Trigger event badge evaluation for attendees. Fire-and-forget to avoid
      // slowing the response. Failures are non-critical — badges can be awarded later.
      // TODO: Replace with a background job queue when one is available.
      if (badgeService && result.goingUserIds.length > 0) {
        void Promise.allSettled(
          result.goingUserIds.map((userId) => badgeService.evaluateEventBadges(userId)),
        );
      }

      return { ok: true, data: { event: result.event } };
    },
  );
}
