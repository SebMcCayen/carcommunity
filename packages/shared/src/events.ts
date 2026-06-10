import type { SubscriptionEntitlement, UserRole, UserStatus } from './users.js';
import { canAccessAdminFeatures, canAccessMemberFeatures, isSuspendedStatus } from './users.js';

// ---------------------------------------------------------------------------
// Status enums
// ---------------------------------------------------------------------------

export const EVENT_STATUSES = ['draft', 'published', 'cancelled', 'completed'] as const;
export type EventStatus = (typeof EVENT_STATUSES)[number];

export const EVENT_RSVP_STATUSES = ['going', 'maybe', 'not_going'] as const;
export type EventRsvpStatus = (typeof EVENT_RSVP_STATUSES)[number];

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const EVENT_ROUTE_PATHS = {
  teasers: '/v1/events/teasers',
  adminEvents: '/v1/admin/events',
} as const;

export function buildEventDetailPath(eventId: string): string {
  return `/v1/events/${eventId}`;
}

export function buildEventRsvpPath(eventId: string): string {
  return `/v1/events/${eventId}/rsvp`;
}

// ---------------------------------------------------------------------------
// Shared contracts
// ---------------------------------------------------------------------------

/**
 * Public teaser: only safe information, no exact location or personal data.
 * Visible to all authenticated users regardless of subscription.
 */
export interface EventTeaser {
  id: string;
  title: string;
  startsAt: string;
  endsAt: string | null;
  approximateArea: string;
  isOfficial: boolean;
  status: EventStatus;
}

/**
 * RSVP summary visible in event detail (aggregated counts only).
 */
export interface EventRsvpSummary {
  going: number;
  maybe: number;
  not_going: number;
}

/**
 * Full event detail — member-only fields included.
 * Never expose internal admin fields here.
 */
export interface EventDetail {
  id: string;
  title: string;
  summary: string | null;
  description: string | null;
  startsAt: string;
  endsAt: string | null;
  locationName: string | null;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  isOfficial: boolean;
  status: EventStatus;
  rsvpSummary: EventRsvpSummary;
  currentUserRsvp: EventRsvpStatus | null;
}

/**
 * Minimal location summary (member-visible only).
 */
export interface EventLocationSummary {
  locationName: string | null;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
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
  rsvpCounts: EventRsvpSummary;
  cancelledAt: string | null;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Request / response contracts
// ---------------------------------------------------------------------------

export interface EventRsvpRequest {
  status: EventRsvpStatus;
}

export interface EventRsvpData {
  eventId: string;
  userId: string;
  status: EventRsvpStatus;
  updatedAt: string;
}

export interface EventTeasersResponse {
  ok: true;
  data: {
    events: EventTeaser[];
  };
  meta: {
    total: number;
    nextCursor: string | null;
  };
}

export interface EventDetailResponse {
  ok: true;
  data: {
    event: EventDetail;
  };
}

export interface EventRsvpResponse {
  ok: true;
  data: {
    rsvp: EventRsvpData;
  };
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

// ---------------------------------------------------------------------------
// Access helpers
// ---------------------------------------------------------------------------

/**
 * Returns true if the user can view event teasers.
 * All authenticated, non-deleted, non-suspended users may view teasers.
 */
export function canViewEventTeaser(input: { role: UserRole; status: UserStatus }): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }
  return true;
}

/**
 * Returns true if the user can view full event details (member-only).
 * Requires active member_monthly subscription.
 * Suspension and deletion override subscription.
 */
export function canViewEventDetails(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  return canAccessMemberFeatures(input);
}

/**
 * Returns true if the user can RSVP to an event.
 * Requires active member_monthly subscription.
 * Suspension and deletion override subscription.
 */
export function canRsvpToEvent(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}): boolean {
  return canAccessMemberFeatures(input);
}

/**
 * Returns true if the user can access admin event views.
 * Requires admin or owner role and a non-suspended, non-deleted status.
 * Does not require member subscription.
 */
export function canAccessEventAdmin(input: { role: UserRole; status: UserStatus }): boolean {
  return canAccessAdminFeatures(input);
}
