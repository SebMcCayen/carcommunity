/**
 * Mobile API client for group drive endpoints.
 *
 * Security notes:
 * - Never log tokens, coordinates, or participant identifiers.
 * - All access rules are enforced by the backend.
 * - Client-side checks are UX hints only.
 */

import {
  buildGroupDriveJoinPath,
  buildGroupDriveLeavePath,
  buildGroupDriveMarkersPath,
  buildGroupDriveSummaryPath,
  buildGroupDriveStatusPath,
  type GroupDriveMarkersResponse,
  type GroupDriveSummaryResponse,
  type JoinGroupDriveResponse,
  type LeaveGroupDriveResponse,
  type UpdateGroupDriveStatusRequest,
  type UpdateGroupDriveStatusResponse,
} from '@carcommunity/shared/group-drive';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

/**
 * An error from the group drive API that carries the HTTP status code.
 */
export class GroupDriveApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'GroupDriveApiError';
  }
}

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
    throw new GroupDriveApiError(
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
    throw new GroupDriveApiError(
      response.status,
      `Group drive request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * Join an event group drive.
 * Does NOT start live location automatically.
 */
export async function joinGroupDrive(
  eventId: string,
  token?: string,
): Promise<JoinGroupDriveResponse> {
  return requestJson<JoinGroupDriveResponse>(
    buildGroupDriveJoinPath(eventId),
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({}),
    },
    token,
  );
}

/**
 * Leave an event group drive.
 * Does NOT stop the user's live location session.
 */
export async function leaveGroupDrive(
  eventId: string,
  token?: string,
): Promise<LeaveGroupDriveResponse> {
  return requestJson<LeaveGroupDriveResponse>(
    buildGroupDriveLeavePath(eventId),
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({}),
    },
    token,
  );
}

/**
 * Update the current user's group drive participant status.
 * Accepted values: joined, on_the_way, arrived.
 */
export async function updateGroupDriveStatus(
  eventId: string,
  request: UpdateGroupDriveStatusRequest,
  token?: string,
): Promise<UpdateGroupDriveStatusResponse> {
  return requestJson<UpdateGroupDriveStatusResponse>(
    buildGroupDriveStatusPath(eventId),
    {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(request),
    },
    token,
  );
}

/**
 * Get the group drive summary.
 * Returns aggregate counts and safe participant list.
 */
export async function loadGroupDriveSummary(
  eventId: string,
  token?: string,
): Promise<GroupDriveSummaryResponse> {
  return requestJson<GroupDriveSummaryResponse>(
    buildGroupDriveSummaryPath(eventId),
    { method: 'GET' },
    token,
  );
}

/**
 * Get visible live location markers for active group drive participants.
 * Only participants with active, non-stale positions are returned.
 */
export async function loadGroupDriveMarkers(
  eventId: string,
  token?: string,
): Promise<GroupDriveMarkersResponse> {
  return requestJson<GroupDriveMarkersResponse>(
    buildGroupDriveMarkersPath(eventId),
    { method: 'GET' },
    token,
  );
}
