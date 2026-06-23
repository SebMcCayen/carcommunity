import type { Event, EventRsvp, Prisma, PrismaClient } from '@prisma/client';
import {
  EVENT_RSVP_STATUSES,
  type AdminEventDetail,
  type AdminEventSummary,
  type EventDetail,
  type EventRsvpStatus,
  type EventRsvpSummary,
  type EventStatus,
  type EventTeaser,
} from '@carcommunity/shared/events';

import { AppError } from './errors.js';

export interface GetEventTeasersResult {
  events: EventTeaser[];
  total: number;
  nextCursor: string | null;
}

export interface GetEventDetailResult {
  event: EventDetail;
}

export interface UpsertRsvpResult {
  eventId: string;
  userId: string;
  status: EventRsvpStatus;
  updatedAt: string;
}

export interface GetAdminEventsResult {
  events: AdminEventSummary[];
  total: number;
  page: number;
  pageSize: number;
}

export interface GetAdminEventsParams {
  page?: number;
  pageSize?: number;
  /** Filter by event status. */
  status?: EventStatus;
  /** When true, only return events whose startsAt is in the future. When false, only return events whose startsAt is in the past. */
  upcoming?: boolean;
  /** Filter to official events only. */
  isOfficial?: boolean;
}

export interface CreateEventInput {
  title: string;
  summary?: string | null;
  description?: string | null;
  startsAt: string;
  endsAt?: string | null;
  approximateArea: string;
  locationName?: string | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  isOfficial?: boolean;
}

export interface UpdateEventInput {
  title?: string;
  summary?: string | null;
  description?: string | null;
  startsAt?: string;
  endsAt?: string | null;
  approximateArea?: string;
  locationName?: string | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  isOfficial?: boolean;
}

/** Fields included as audit metadata for an event update. Safe fields only. */
interface UpdateAuditMeta {
  changedFields: string[];
}

function toEventTeaser(event: Pick<Event, 'id' | 'title' | 'startsAt' | 'endsAt' | 'approximateArea' | 'isOfficial' | 'status'>): EventTeaser {
  return {
    id: event.id,
    title: event.title,
    startsAt: event.startsAt.toISOString(),
    endsAt: event.endsAt ? event.endsAt.toISOString() : null,
    approximateArea: event.approximateArea,
    isOfficial: event.isOfficial,
    status: event.status,
  };
}

function toRsvpSummary(rsvps: Pick<EventRsvp, 'status'>[]): EventRsvpSummary {
  const summary: EventRsvpSummary = { going: 0, maybe: 0, not_going: 0 };
  for (const rsvp of rsvps) {
    summary[rsvp.status] += 1;
  }
  return summary;
}

function toAdminEventSummary(
  event: Pick<Event, 'id' | 'title' | 'status' | 'isOfficial' | 'startsAt' | 'endsAt' | 'approximateArea' | 'cancelledAt' | 'createdAt' | 'updatedAt'>,
  rsvpCounts: EventRsvpSummary,
): AdminEventSummary {
  return {
    id: event.id,
    title: event.title,
    status: event.status,
    isOfficial: event.isOfficial,
    startsAt: event.startsAt.toISOString(),
    endsAt: event.endsAt ? event.endsAt.toISOString() : null,
    approximateArea: event.approximateArea,
    rsvpCounts,
    cancelledAt: event.cancelledAt ? event.cancelledAt.toISOString() : null,
    createdAt: event.createdAt.toISOString(),
    updatedAt: event.updatedAt.toISOString(),
  };
}

function toAdminEventDetail(
  event: Pick<
    Event,
    | 'id' | 'title' | 'summary' | 'description' | 'status'
    | 'startsAt' | 'endsAt' | 'approximateArea'
    | 'locationName' | 'address' | 'latitude' | 'longitude'
    | 'isOfficial' | 'createdByUserId' | 'createdAt' | 'updatedAt' | 'cancelledAt'
  >,
  rsvpCounts: EventRsvpSummary,
): AdminEventDetail {
  return {
    id: event.id,
    title: event.title,
    summary: event.summary,
    description: event.description,
    status: event.status,
    startsAt: event.startsAt.toISOString(),
    endsAt: event.endsAt ? event.endsAt.toISOString() : null,
    approximateArea: event.approximateArea,
    locationName: event.locationName,
    address: event.address,
    latitude: event.latitude,
    longitude: event.longitude,
    isOfficial: event.isOfficial,
    createdByUserId: event.createdByUserId,
    createdAt: event.createdAt.toISOString(),
    updatedAt: event.updatedAt.toISOString(),
    cancelledAt: event.cancelledAt ? event.cancelledAt.toISOString() : null,
    rsvpCounts,
  };
}

const ADMIN_EVENT_SELECT = {
  id: true,
  title: true,
  summary: true,
  description: true,
  status: true,
  startsAt: true,
  endsAt: true,
  approximateArea: true,
  locationName: true,
  address: true,
  latitude: true,
  longitude: true,
  isOfficial: true,
  createdByUserId: true,
  createdAt: true,
  updatedAt: true,
  cancelledAt: true,
} as const;

async function getRsvpCountsForEvent(prisma: PrismaClient, eventId: string): Promise<EventRsvpSummary> {
  const groups = await prisma.eventRsvp.groupBy({
    by: ['eventId', 'status'],
    _count: { status: true },
    where: { eventId },
  });
  const summary: EventRsvpSummary = { going: 0, maybe: 0, not_going: 0 };
  for (const g of groups) {
    summary[g.status as EventRsvpStatus] = g._count.status;
  }
  return summary;
}

export class EventService {
  constructor(private readonly prisma: PrismaClient) {}

  public async getEventTeasers(params: { now?: Date; cursor?: string; take?: number } = {}): Promise<GetEventTeasersResult> {
    const now = params.now ?? new Date();
    const take = params.take ?? 20;

    const where: Prisma.EventWhereInput = {
      status: 'published',
      startsAt: {
        gte: now,
      },
    };

    const [total, events] = await this.prisma.$transaction([
      this.prisma.event.count({ where }),
      this.prisma.event.findMany({
        where,
        orderBy: { startsAt: 'asc' },
        take: take + 1,
        ...(params.cursor ? { cursor: { id: params.cursor }, skip: 1 } : {}),
        select: {
          id: true,
          title: true,
          startsAt: true,
          endsAt: true,
          approximateArea: true,
          isOfficial: true,
          status: true,
        },
      }),
    ]);

    const hasNextPage = events.length > take;
    const page = hasNextPage ? events.slice(0, take) : events;
    const nextCursor = hasNextPage ? (page[page.length - 1]?.id ?? null) : null;

    return {
      events: page.map(toEventTeaser),
      total,
      nextCursor,
    };
  }

  public async getEventDetail(params: {
    eventId: string;
    viewerUserId: string;
  }): Promise<GetEventDetailResult> {
    const event = await this.prisma.event.findUnique({
      where: { id: params.eventId },
      include: {
        rsvps: {
          select: { status: true, userId: true },
        },
      },
    });

    if (!event || event.status === 'draft') {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    const currentUserRsvp = event.rsvps.find((r) => r.userId === params.viewerUserId);
    const rsvpSummary = toRsvpSummary(event.rsvps);

    return {
      event: {
        id: event.id,
        title: event.title,
        summary: event.summary,
        description: event.description,
        startsAt: event.startsAt.toISOString(),
        endsAt: event.endsAt ? event.endsAt.toISOString() : null,
        locationName: event.locationName,
        address: event.address,
        latitude: event.latitude,
        longitude: event.longitude,
        isOfficial: event.isOfficial,
        status: event.status,
        rsvpSummary,
        currentUserRsvp: currentUserRsvp ? (currentUserRsvp.status as EventRsvpStatus) : null,
      },
    };
  }

  public async upsertRsvp(params: {
    eventId: string;
    userId: string;
    status: EventRsvpStatus;
  }): Promise<UpsertRsvpResult> {
    const event = await this.prisma.event.findUnique({
      where: { id: params.eventId },
      select: { id: true, status: true },
    });

    if (!event || event.status === 'draft') {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (event.status === 'cancelled' || event.status === 'completed') {
      throw new AppError(400, 'validation_error', 'Cannot RSVP to a cancelled or completed event.');
    }

    const rsvp = await this.prisma.eventRsvp.upsert({
      where: {
        eventId_userId: {
          eventId: params.eventId,
          userId: params.userId,
        },
      },
      create: {
        eventId: params.eventId,
        userId: params.userId,
        status: params.status,
      },
      update: {
        status: params.status,
      },
    });

    return {
      eventId: rsvp.eventId,
      userId: rsvp.userId,
      status: rsvp.status as EventRsvpStatus,
      updatedAt: rsvp.updatedAt.toISOString(),
    };
  }

  public async getAdminEvents(params: GetAdminEventsParams = {}): Promise<GetAdminEventsResult> {
    const page = Math.max(1, params.page ?? 1);
    const pageSize = Math.min(100, Math.max(1, params.pageSize ?? 20));
    const skip = (page - 1) * pageSize;

    const where: Prisma.EventWhereInput = {};
    if (params.status !== undefined) {
      where.status = params.status;
    }
    if (params.upcoming === true) {
      where.startsAt = { gte: new Date() };
    } else if (params.upcoming === false) {
      where.startsAt = { lt: new Date() };
    }
    if (params.isOfficial !== undefined) {
      where.isOfficial = params.isOfficial;
    }

    const [total, events] = await this.prisma.$transaction([
      this.prisma.event.count({ where }),
      this.prisma.event.findMany({
        where,
        orderBy: { startsAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          title: true,
          status: true,
          isOfficial: true,
          startsAt: true,
          endsAt: true,
          approximateArea: true,
          cancelledAt: true,
          createdAt: true,
          updatedAt: true,
        },
      }),
    ]);

    const eventIds = events.map((e) => e.id);

    const rsvpGroups =
      eventIds.length > 0
        ? await this.prisma.eventRsvp.groupBy({
            by: ['eventId', 'status'],
            _count: { status: true },
            where: { eventId: { in: eventIds } },
          })
        : [];

    const rsvpCountsByEventId = new Map<string, EventRsvpSummary>();
    for (const group of rsvpGroups) {
      if (!rsvpCountsByEventId.has(group.eventId)) {
        rsvpCountsByEventId.set(group.eventId, { going: 0, maybe: 0, not_going: 0 });
      }
      const summary = rsvpCountsByEventId.get(group.eventId)!;
      summary[group.status as EventRsvpStatus] = group._count.status;
    }

    return {
      events: events.map((e) =>
        toAdminEventSummary(e, rsvpCountsByEventId.get(e.id) ?? { going: 0, maybe: 0, not_going: 0 }),
      ),
      total,
      page,
      pageSize,
    };
  }

  public async getAdminEvent(eventId: string): Promise<{ event: AdminEventDetail }> {
    const event = await this.prisma.event.findUnique({
      where: { id: eventId },
      select: ADMIN_EVENT_SELECT,
    });

    if (!event) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    const rsvpCounts = await getRsvpCountsForEvent(this.prisma, eventId);
    return { event: toAdminEventDetail(event, rsvpCounts) };
  }

  public async createEvent(params: {
    actorUserId: string;
    data: CreateEventInput;
  }): Promise<{ event: AdminEventDetail }> {
    const startsAt = new Date(params.data.startsAt);
    const endsAt = params.data.endsAt ? new Date(params.data.endsAt) : null;

    if (endsAt && endsAt <= startsAt) {
      throw new AppError(400, 'validation_error', 'endsAt must be after startsAt.');
    }

    const hasLat = params.data.latitude != null;
    const hasLon = params.data.longitude != null;
    if (hasLat !== hasLon) {
      throw new AppError(400, 'validation_error', 'latitude and longitude must both be provided or both omitted.');
    }

    const event = await this.prisma.$transaction(async (tx) => {
      const created = await tx.event.create({
        data: {
          title: params.data.title,
          summary: params.data.summary ?? null,
          description: params.data.description ?? null,
          status: 'draft',
          startsAt,
          endsAt,
          approximateArea: params.data.approximateArea,
          locationName: params.data.locationName ?? null,
          address: params.data.address ?? null,
          latitude: params.data.latitude ?? null,
          longitude: params.data.longitude ?? null,
          isOfficial: params.data.isOfficial ?? false,
          createdByUserId: params.actorUserId,
        },
        select: ADMIN_EVENT_SELECT,
      });

      await tx.auditLog.create({
        data: {
          actorUserId: params.actorUserId,
          action: 'event.create',
          entityType: 'event',
          entityId: created.id,
          reason: null,
          metadata: {
            title: created.title,
            status: created.status,
            startsAt: created.startsAt.toISOString(),
          },
        },
      });

      return created;
    });

    return { event: toAdminEventDetail(event, { going: 0, maybe: 0, not_going: 0 }) };
  }

  public async updateEvent(params: {
    actorUserId: string;
    eventId: string;
    data: UpdateEventInput;
  }): Promise<{ event: AdminEventDetail }> {
    const existing = await this.prisma.event.findUnique({
      where: { id: params.eventId },
      select: { id: true, status: true, startsAt: true, endsAt: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (existing.status === 'cancelled' || existing.status === 'completed') {
      throw new AppError(409, 'conflict', 'Cannot update a cancelled or completed event.');
    }

    const startsAt = params.data.startsAt ? new Date(params.data.startsAt) : existing.startsAt;
    const endsAtRaw = 'endsAt' in params.data ? params.data.endsAt : existing.endsAt;
    const endsAt = endsAtRaw ? new Date(endsAtRaw) : null;

    if (endsAt && endsAt <= startsAt) {
      throw new AppError(400, 'validation_error', 'endsAt must be after startsAt.');
    }

    const newLat = 'latitude' in params.data ? params.data.latitude : undefined;
    const newLon = 'longitude' in params.data ? params.data.longitude : undefined;

    if (newLat !== undefined || newLon !== undefined) {
      const finalLat = newLat !== undefined ? newLat : null;
      const finalLon = newLon !== undefined ? newLon : null;
      if ((finalLat != null) !== (finalLon != null)) {
        throw new AppError(400, 'validation_error', 'latitude and longitude must both be provided or both omitted.');
      }
    }

    const changedFields: string[] = [];
    if (params.data.title !== undefined) changedFields.push('title');
    if ('summary' in params.data) changedFields.push('summary');
    if ('description' in params.data) changedFields.push('description');
    if (params.data.startsAt !== undefined) changedFields.push('startsAt');
    if ('endsAt' in params.data) changedFields.push('endsAt');
    if (params.data.approximateArea !== undefined) changedFields.push('approximateArea');
    if ('locationName' in params.data) changedFields.push('locationName');
    if ('address' in params.data) changedFields.push('address');
    if ('latitude' in params.data) changedFields.push('latitude');
    if ('longitude' in params.data) changedFields.push('longitude');
    if (params.data.isOfficial !== undefined) changedFields.push('isOfficial');

    const updateData: Prisma.EventUpdateInput = {};
    if (params.data.title !== undefined) updateData.title = params.data.title;
    if ('summary' in params.data) updateData.summary = params.data.summary;
    if ('description' in params.data) updateData.description = params.data.description;
    if (params.data.startsAt !== undefined) updateData.startsAt = startsAt;
    if ('endsAt' in params.data) updateData.endsAt = endsAt;
    if (params.data.approximateArea !== undefined) updateData.approximateArea = params.data.approximateArea;
    if ('locationName' in params.data) updateData.locationName = params.data.locationName;
    if ('address' in params.data) updateData.address = params.data.address;
    if ('latitude' in params.data) updateData.latitude = params.data.latitude;
    if ('longitude' in params.data) updateData.longitude = params.data.longitude;
    if (params.data.isOfficial !== undefined) updateData.isOfficial = params.data.isOfficial;

    const auditMeta: UpdateAuditMeta = { changedFields };

    const updated = await this.prisma.$transaction(async (tx) => {
      const result = await tx.event.update({
        where: { id: params.eventId },
        data: updateData,
        select: ADMIN_EVENT_SELECT,
      });

      await tx.auditLog.create({
        data: {
          actorUserId: params.actorUserId,
          action: 'event.update',
          entityType: 'event',
          entityId: params.eventId,
          reason: null,
          metadata: auditMeta as unknown as Prisma.InputJsonValue,
        },
      });

      return result;
    });

    const rsvpCounts = await getRsvpCountsForEvent(this.prisma, params.eventId);
    return { event: toAdminEventDetail(updated, rsvpCounts) };
  }

  public async publishEvent(params: {
    actorUserId: string;
    eventId: string;
  }): Promise<{ event: AdminEventDetail }> {
    const existing = await this.prisma.event.findUnique({
      where: { id: params.eventId },
      select: { id: true, status: true, title: true, startsAt: true, approximateArea: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (existing.status !== 'draft') {
      throw new AppError(409, 'conflict', 'Only draft events can be published.');
    }

    if (!existing.title || !existing.approximateArea) {
      throw new AppError(400, 'validation_error', 'Event must have title and approximateArea before publishing.');
    }

    const now = new Date();
    if (existing.startsAt < now) {
      throw new AppError(400, 'validation_error', 'Cannot publish an event whose start time is in the past.');
    }

    const published = await this.prisma.$transaction(async (tx) => {
      const result = await tx.event.update({
        where: { id: params.eventId },
        data: { status: 'published' },
        select: ADMIN_EVENT_SELECT,
      });

      await tx.auditLog.create({
        data: {
          actorUserId: params.actorUserId,
          action: 'event.publish',
          entityType: 'event',
          entityId: params.eventId,
          reason: null,
          metadata: {
            title: existing.title,
            startsAt: existing.startsAt.toISOString(),
          },
        },
      });

      return result;
    });

    const rsvpCounts = await getRsvpCountsForEvent(this.prisma, params.eventId);
    return { event: toAdminEventDetail(published, rsvpCounts) };
  }

  public async cancelEvent(params: {
    actorUserId: string;
    eventId: string;
    reason: string;
  }): Promise<{ event: AdminEventDetail }> {
    const existing = await this.prisma.event.findUnique({
      where: { id: params.eventId },
      select: { id: true, status: true, title: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (existing.status === 'cancelled') {
      throw new AppError(409, 'conflict', 'Event is already cancelled.');
    }

    if (existing.status === 'completed') {
      throw new AppError(409, 'conflict', 'Completed events cannot be cancelled.');
    }

    const cancelled = await this.prisma.$transaction(async (tx) => {
      const result = await tx.event.update({
        where: { id: params.eventId },
        data: {
          status: 'cancelled',
          cancelledAt: new Date(),
        },
        select: ADMIN_EVENT_SELECT,
      });

      await tx.auditLog.create({
        data: {
          actorUserId: params.actorUserId,
          action: 'event.cancel',
          entityType: 'event',
          entityId: params.eventId,
          reason: params.reason,
          metadata: { title: existing.title },
        },
      });

      return result;
    });

    const rsvpCounts = await getRsvpCountsForEvent(this.prisma, params.eventId);
    return { event: toAdminEventDetail(cancelled, rsvpCounts) };
  }

  /**
   * Marks a published event as completed.
   * Only published events can be completed.
   * Returns the list of user IDs who RSVPed 'going' so badge evaluation can be
   * triggered by the caller after the database update.
   *
   * Badge evaluation integration:
   *  - After this method returns, the caller must trigger BadgeService.evaluateEventBadges()
   *    for each userId in goingUserIds.
   *  - TODO: Replace RSVP-proxy attendance with verified check-in records once
   *    an event attendance/check-in system is implemented.
   */
  public async completeEvent(params: {
    actorUserId: string;
    eventId: string;
  }): Promise<{ event: AdminEventDetail; goingUserIds: string[] }> {
    const existing = await this.prisma.event.findUnique({
      where: { id: params.eventId },
      select: { id: true, status: true, title: true },
    });

    if (!existing) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }

    if (existing.status !== 'published') {
      throw new AppError(409, 'conflict', 'Only published events can be marked as completed.');
    }

    const completed = await this.prisma.$transaction(async (tx) => {
      const result = await tx.event.update({
        where: { id: params.eventId },
        data: { status: 'completed' },
        select: ADMIN_EVENT_SELECT,
      });

      await tx.auditLog.create({
        data: {
          actorUserId: params.actorUserId,
          action: 'event.complete',
          entityType: 'event',
          entityId: params.eventId,
          reason: null,
          metadata: { title: existing.title },
        },
      });

      return result;
    });

    // Collect user IDs with 'going' RSVP for badge evaluation by the caller.
    // This is bounded by the number of attendees of a single event.
    const goingRsvps = await this.prisma.eventRsvp.findMany({
      where: { eventId: params.eventId, status: 'going' },
      select: { userId: true },
    });
    const goingUserIds = goingRsvps.map((r) => r.userId);

    const rsvpCounts = await getRsvpCountsForEvent(this.prisma, params.eventId);
    return { event: toAdminEventDetail(completed, rsvpCounts), goingUserIds };
  }
}

/**
 * Validates that the given value is a valid EventRsvpStatus.
 */
export function isValidEventRsvpStatus(value: unknown): value is EventRsvpStatus {
  return EVENT_RSVP_STATUSES.includes(value as EventRsvpStatus);
}
