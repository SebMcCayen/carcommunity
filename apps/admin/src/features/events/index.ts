/**
 * Events feature module for the admin portal.
 *
 * Provides shared types, helpers, API client functions, and hooks for the
 * events admin area. Pages in src/app/events/ import from here.
 *
 * All admin operations are validated by the backend. Client-side checks are
 * for user experience only and are not security boundaries.
 */

import {
  EVENT_ROUTE_PATHS,
  buildAdminEventCancelPath,
  buildAdminEventPath,
  buildAdminEventPublishPath,
  type AdminEventDetail,
  type AdminEventResponse,
  type AdminEventsResponse,
  type AdminEventSummary,
  type CancelEventRequest,
  type CreateEventRequest,
  type EventStatus,
  type UpdateEventRequest,
} from '@carcommunity/shared/events';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  AdminEventDetail,
  AdminEventSummary,
  CancelEventRequest,
  CreateEventRequest,
  EventStatus,
  UpdateEventRequest,
};
export { ApiError };

// ---------------------------------------------------------------------------
// Swedish status labels
// ---------------------------------------------------------------------------

/**
 * Returns a human-readable Swedish label for an event status.
 */
export function formatEventStatus(status: EventStatus): string {
  switch (status) {
    case 'draft':
      return 'Utkast';
    case 'published':
      return 'Publicerat';
    case 'cancelled':
      return 'Inställt';
    case 'completed':
      return 'Genomfört';
  }
}

/**
 * Returns a CSS class name fragment based on status for badge styling.
 */
export function eventStatusVariant(status: EventStatus): 'draft' | 'published' | 'cancelled' | 'completed' {
  return status;
}

/**
 * Returns true if the event is upcoming (starts in the future and is published).
 */
export function isUpcomingEvent(event: Pick<AdminEventSummary, 'status' | 'startsAt'>): boolean {
  return event.status === 'published' && new Date(event.startsAt) > new Date();
}

// ---------------------------------------------------------------------------
// API client helpers
// ---------------------------------------------------------------------------

export interface LoadAdminEventsParams {
  page?: number;
  pageSize?: number;
  status?: EventStatus;
  upcoming?: boolean;
  isOfficial?: boolean;
  token?: string;
}

/**
 * Loads the paginated admin event list.
 */
export async function loadAdminEvents(params: LoadAdminEventsParams = {}): Promise<AdminEventsResponse> {
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set('page', String(params.page));
  if (params.pageSize !== undefined) query.set('pageSize', String(params.pageSize));
  if (params.status !== undefined) query.set('status', params.status);
  if (params.upcoming !== undefined) query.set('upcoming', String(params.upcoming));
  if (params.isOfficial !== undefined) query.set('isOfficial', String(params.isOfficial));

  const qs = query.toString();
  return apiRequest<AdminEventsResponse>(`${EVENT_ROUTE_PATHS.adminEvents}${qs ? `?${qs}` : ''}`, {
    token: params.token,
  });
}

/**
 * Loads a single event for admin detail/edit view.
 */
export async function loadAdminEvent(eventId: string, token?: string): Promise<AdminEventResponse> {
  return apiRequest<AdminEventResponse>(buildAdminEventPath(eventId), { token });
}

/**
 * Creates a new draft event.
 * Status and creator are always set by the backend — they cannot be passed here.
 */
export async function createAdminEvent(
  data: CreateEventRequest,
  token?: string,
): Promise<AdminEventResponse> {
  return apiRequest<AdminEventResponse>(EVENT_ROUTE_PATHS.adminEvents, {
    method: 'POST',
    body: data,
    token,
  });
}

/**
 * Updates an existing draft or published event.
 * Status changes must use publishAdminEvent or cancelAdminEvent.
 */
export async function updateAdminEvent(
  eventId: string,
  data: UpdateEventRequest,
  token?: string,
): Promise<AdminEventResponse> {
  return apiRequest<AdminEventResponse>(buildAdminEventPath(eventId), {
    method: 'PATCH',
    body: data,
    token,
  });
}

/**
 * Publishes a draft event.
 * The backend validates required fields and that the start time is in the future.
 */
export async function publishAdminEvent(eventId: string, token?: string): Promise<AdminEventResponse> {
  return apiRequest<AdminEventResponse>(buildAdminEventPublishPath(eventId), {
    method: 'POST',
    token,
  });
}

/**
 * Cancels an event with a mandatory reason.
 * The event is preserved in the database — it is never hard-deleted.
 */
export async function cancelAdminEvent(
  eventId: string,
  request: CancelEventRequest,
  token?: string,
): Promise<AdminEventResponse> {
  return apiRequest<AdminEventResponse>(buildAdminEventCancelPath(eventId), {
    method: 'POST',
    body: request,
    token,
  });
}

