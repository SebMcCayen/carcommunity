import {
  DEFAULT_LIVE_LOCATION_PAGE_SIZE,
  LIVE_LOCATION_ROUTE_PATHS,
  buildLiveLocationPositionPath,
  buildLiveLocationStopPath,
  type HideMeNowResponse,
  type LiveLocationPositionUpdateResponse,
  type LiveLocationStartRequest,
  type LiveLocationStartResponse,
  type LiveLocationStopRequest,
  type LiveLocationStopResponse,
  type LiveLocationUpdateRequest,
  type PublicLiveLocationMarkerResponse,
} from '@carcommunity/shared/live-location';

import { publicEnv } from '../config/env';

// TODO: Set EXPO_PUBLIC_API_BASE_URL in your .env file before making live location requests.
const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { authorization: 'Bearer ' + token } : {};

/**
 * Typed error thrown when an API request returns a non-2xx HTTP status.
 * Use the `statusCode` to distinguish authentication (401), access (403),
 * network, and server errors without relying on error message parsing.
 */
export class LiveLocationApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'LiveLocationApiError';
  }
}

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    // TODO: Configure EXPO_PUBLIC_API_BASE_URL before enabling live location requests.
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new LiveLocationApiError(response.status, `Live location request failed with status ${response.status}`);
  }

  return (await response.json()) as TResponse;
}

/**
 * TODO: Keep session start behind an explicit user opt-in flow.
 * TODO: Confirm the app is in the foreground before starting updates.
 * TODO: Add background mode handling only during an active session.
 * TODO: Add Android foreground notification when background updates are introduced.
 * TODO: Add iOS background location handling only during an active session.
 * TODO: Add safe-driving UI that avoids encouraging interaction while driving.
 * TODO: Throttle updates to roughly 25-50 meters or 5-10 seconds once device location is wired in.
 */
export async function startLiveLocationSession(
  body: LiveLocationStartRequest,
  token?: string,
): Promise<LiveLocationStartResponse> {
  return requestJson<LiveLocationStartResponse>(LIVE_LOCATION_ROUTE_PATHS.sessions, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(body),
  });
}

export async function updateLiveLocationPosition(
  sessionId: string,
  body: LiveLocationUpdateRequest,
  token?: string,
): Promise<LiveLocationPositionUpdateResponse> {
  return requestJson<LiveLocationPositionUpdateResponse>(buildLiveLocationPositionPath(sessionId), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(body),
  });
}

export async function stopLiveLocationSession(
  sessionId: string,
  body: LiveLocationStopRequest = { reason: 'user_stop' },
  token?: string,
): Promise<LiveLocationStopResponse> {
  return requestJson<LiveLocationStopResponse>(buildLiveLocationStopPath(sessionId), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(body),
  });
}

export async function hideMeNow(token?: string): Promise<HideMeNowResponse> {
  return requestJson<HideMeNowResponse>(LIVE_LOCATION_ROUTE_PATHS.hideMeNow, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify({}),
  });
}

export async function loadLiveLocationMarkers(
  page = 1,
  pageSize = DEFAULT_LIVE_LOCATION_PAGE_SIZE,
  token?: string,
): Promise<PublicLiveLocationMarkerResponse> {
  const searchParams = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  });

  return requestJson<PublicLiveLocationMarkerResponse>(
    `${LIVE_LOCATION_ROUTE_PATHS.markers}?${searchParams.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}
