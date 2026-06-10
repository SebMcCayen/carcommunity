import type { Event, EventRsvp, Prisma, PrismaClient } from '@prisma/client';
import {
  EVENT_RSVP_STATUSES,
  type AdminEventSummary,
  type EventDetail,
  type EventRsvpStatus,
  type EventRsvpSummary,
  type EventTeaser,
} from '@carcommunity/shared/events';

import { AppError } from './errors.js';

export interface GetEventTeasersResult {
  events: EventTeaser[];
  total: number;
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
  event: Pick<Event, 'id' | 'title' | 'status' | 'isOfficial' | 'startsAt' | 'endsAt' | 'cancelledAt' | 'createdAt'>,
  rsvps: Pick<EventRsvp, 'status'>[],
): AdminEventSummary {
  return {
    id: event.id,
    title: event.title,
    status: event.status,
    isOfficial: event.isOfficial,
    startsAt: event.startsAt.toISOString(),
    endsAt: event.endsAt ? event.endsAt.toISOString() : null,
    rsvpCounts: toRsvpSummary(rsvps),
    cancelledAt: event.cancelledAt ? event.cancelledAt.toISOString() : null,
    createdAt: event.createdAt.toISOString(),
  };
}

export class EventService {
  constructor(private readonly prisma: PrismaClient) {}

  public async getEventTeasers(params: { now?: Date } = {}): Promise<GetEventTeasersResult> {
    const now = params.now ?? new Date();

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

    return {
      events: events.map(toEventTeaser),
      total,
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

  public async getAdminEvents(): Promise<GetAdminEventsResult> {
    const events = await this.prisma.event.findMany({
      orderBy: { startsAt: 'desc' },
      select: {
        id: true,
        title: true,
        status: true,
        isOfficial: true,
        startsAt: true,
        endsAt: true,
        cancelledAt: true,
        createdAt: true,
        rsvps: {
          select: { status: true },
        },
      },
    });

    return {
      events: events.map((e) => toAdminEventSummary(e, e.rsvps)),
      total: events.length,
    };
  }
}

/**
 * Validates that the given value is a valid EventRsvpStatus.
 */
export function isValidEventRsvpStatus(value: unknown): value is EventRsvpStatus {
  return EVENT_RSVP_STATUSES.includes(value as EventRsvpStatus);
}
