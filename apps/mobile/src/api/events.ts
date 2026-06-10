import {
  EVENT_ROUTE_PATHS,
  buildEventDetailPath,
  buildEventRsvpPath,
  type AdminEventsResponse,
  type EventDetailResponse,
  type EventRsvpRequest,
  type EventRsvpResponse,
  type EventTeasersResponse,
} from '@carcommunity/shared/events';

import { publicEnv } from '../config/env';

// TODO: Set EXPO_PUBLIC_API_BASE_URL in your .env file before making events requests.
const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    // TODO: Configure EXPO_PUBLIC_API_BASE_URL before enabling events requests.
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new Error(`Events request failed with status ${response.status}`);
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetch upcoming published event teasers.
 * Available to all authenticated users regardless of subscription.
 * Does not expose exact location or address.
 */
export async function loadEventTeasers(): Promise<EventTeasersResponse> {
  return requestJson<EventTeasersResponse>(EVENT_ROUTE_PATHS.teasers, {
    method: 'GET',
  });
}

/**
 * Fetch full event details.
 * Requires active member_monthly subscription.
 */
export async function loadEventDetail(eventId: string): Promise<EventDetailResponse> {
  return requestJson<EventDetailResponse>(buildEventDetailPath(eventId), {
    method: 'GET',
  });
}

/**
 * Submit or update RSVP for an event.
 * Requires active member_monthly subscription.
 */
export async function submitEventRsvp(
  eventId: string,
  body: EventRsvpRequest,
): Promise<EventRsvpResponse> {
  return requestJson<EventRsvpResponse>(buildEventRsvpPath(eventId), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });
}

/**
 * Fetch admin event summary.
 * Requires admin or owner role.
 */
export async function loadAdminEvents(): Promise<AdminEventsResponse> {
  return requestJson<AdminEventsResponse>(EVENT_ROUTE_PATHS.adminEvents, {
    method: 'GET',
  });
}
