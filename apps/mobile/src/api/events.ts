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

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

/**
 * An error from the events API that carries the HTTP status code.
 */
export class EventApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'EventApiError';
  }
}

/**
 * Build Authorization header map. Never log the token value.
 */
function bearerHeaders(token?: string): Record<string, string> {
  if (!token) return {};
  return { Authorization: 'Bearer ' + token };
}

async function requestJson<TResponse>(
  path: string,
  init?: RequestInit,
  token?: string,
): Promise<TResponse> {
  if (!base) {
    throw new EventApiError(
      0,
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const headers: Record<string, string> = {
    ...(init?.headers as Record<string, string> | undefined),
    ...bearerHeaders(token),
  };

  const response = await fetch(buildUrl(path), { ...init, headers });

  if (!response.ok) {
    throw new EventApiError(response.status, `Events request failed with status ${response.status}`);
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetch upcoming published event teasers.
 * Available to all authenticated users regardless of subscription.
 * Does not expose exact location or address.
 */
export async function loadEventTeasers(params?: {
  cursor?: string;
  take?: number;
  token?: string;
}): Promise<EventTeasersResponse> {
  const query = new URLSearchParams();
  if (params?.cursor) query.set('cursor', params.cursor);
  if (params?.take !== undefined) query.set('take', String(params.take));
  const qs = query.toString();
  return requestJson<EventTeasersResponse>(
    qs ? `${EVENT_ROUTE_PATHS.teasers}?${qs}` : EVENT_ROUTE_PATHS.teasers,
    { method: 'GET' },
    params?.token,
  );
}

/**
 * Fetch full event details.
 * Requires active member_monthly subscription.
 * The token must be included; free users receive a 403 from the backend.
 */
export async function loadEventDetails(
  eventId: string,
  token?: string,
): Promise<EventDetailResponse> {
  return requestJson<EventDetailResponse>(buildEventDetailPath(eventId), { method: 'GET' }, token);
}

/**
 * Submit or update RSVP for an event.
 * Requires active member_monthly subscription.
 */
export async function updateEventRsvp(
  eventId: string,
  body: EventRsvpRequest,
  token?: string,
): Promise<EventRsvpResponse> {
  return requestJson<EventRsvpResponse>(
    buildEventRsvpPath(eventId),
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    },
    token,
  );
}

/**
 * Fetch admin event summary.
 * Requires admin or owner role.
 */
export async function loadAdminEvents(token?: string): Promise<AdminEventsResponse> {
  return requestJson<AdminEventsResponse>(
    EVENT_ROUTE_PATHS.adminEvents,
    { method: 'GET' },
    token,
  );
}
