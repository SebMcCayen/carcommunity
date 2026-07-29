// ---------------------------------------------------------------------------
// Status enums
// ---------------------------------------------------------------------------

const EVENT_STATUSES = ['draft', 'published', 'cancelled', 'completed'] as const;
export type EventStatus = (typeof EVENT_STATUSES)[number];

/**
 * RSVP summary visible in event detail (aggregated counts only).
 */
export interface EventRsvpSummary {
  going: number;
  maybe: number;
  not_going: number;
}

/**
 * Admin-facing event summary with operational context.
 * Only returned to admin/owner roles.
 */
export interface AdminEventSummary {
  id: string;
  title: string;
  status: EventStatus;
  isOfficial: boolean;
  startsAt: string;
  endsAt: string | null;
  approximateArea: string;
  rsvpCounts: EventRsvpSummary;
  cancelledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Full admin event detail — all fields including exact location.
 * Only returned to admin/owner roles.
 */
export interface AdminEventDetail {
  id: string;
  title: string;
  summary: string | null;
  description: string | null;
  status: EventStatus;
  startsAt: string;
  endsAt: string | null;
  approximateArea: string;
  locationName: string | null;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  isOfficial: boolean;
  createdByUserId: string | null;
  createdAt: string;
  updatedAt: string;
  cancelledAt: string | null;
  rsvpCounts: EventRsvpSummary;
}

export interface AdminEventsResponse {
  ok: true;
  data: {
    events: AdminEventSummary[];
  };
  meta: {
    total: number;
    page: number;
    pageSize: number;
  };
}

export interface AdminEventResponse {
  ok: true;
  data: {
    event: AdminEventDetail;
  };
}

// ---------------------------------------------------------------------------
// Create / update / publish / cancel contracts
//
// `events.create` is callable by an active member as well as an admin/owner
// (a member's event is published immediately, attributed via createdByRole,
// and has isOfficial forced false). Update / publish / cancel stay admin-only.
// ---------------------------------------------------------------------------

export interface CreateEventRequest {
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

export interface UpdateEventRequest {
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

export interface CancelEventRequest {
  reason: string;
}

