import {
  DEFAULT_SAVED_DRIVES_PAGE_SIZE,
  SAVED_DRIVES_ROUTE_PATHS,
  type DeleteSavedDriveResponse,
  type DiscardDriveResponse,
  type PaginatedSavedDrivesResponse,
  type PostDriveSummaryResponse,
  type SaveDriveResponse,
  type SavedDriveDetailResponse,
} from '@carcommunity/shared/saved-drives';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { authorization: 'Bearer ' + token } : {};

/**
 * Typed error thrown when a saved-drives API request returns a non-2xx status.
 */
export class SavedDrivesApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'SavedDrivesApiError';
  }
}

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new SavedDrivesApiError(
      response.status,
      `Saved drives request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetch the post-drive summary for a stopped session.
 * Does not persist a saved drive.
 */
export async function getPostDriveSummary(
  sessionId: string,
  token?: string,
): Promise<PostDriveSummaryResponse> {
  return requestJson<PostDriveSummaryResponse>(
    SAVED_DRIVES_ROUTE_PATHS.postDriveSummary(sessionId),
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}

/**
 * Save a drive explicitly. Requires member subscription.
 * Must only be called on explicit user action — never automatically.
 */
export async function saveDrive(
  sessionId: string,
  token?: string,
): Promise<SaveDriveResponse> {
  return requestJson<SaveDriveResponse>(SAVED_DRIVES_ROUTE_PATHS.saveDrive(sessionId), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify({}),
  });
}

/**
 * Discard the drive for a session.
 * Deletes any temporary route data associated with the session.
 * Does not create a saved drive.
 */
export async function discardDrive(
  sessionId: string,
  token?: string,
): Promise<DiscardDriveResponse> {
  return requestJson<DiscardDriveResponse>(SAVED_DRIVES_ROUTE_PATHS.discardDrive(sessionId), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify({}),
  });
}

/**
 * List the current user's saved drives, newest first.
 */
export async function listSavedDrives(
  page = 1,
  pageSize = DEFAULT_SAVED_DRIVES_PAGE_SIZE,
  token?: string,
): Promise<PaginatedSavedDrivesResponse> {
  const searchParams = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  });
  return requestJson<PaginatedSavedDrivesResponse>(
    `${SAVED_DRIVES_ROUTE_PATHS.list}?${searchParams.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}

/**
 * Get saved drive detail for the authenticated owner.
 * routeOverview is only included for eligible members.
 */
export async function getSavedDrive(
  driveId: string,
  token?: string,
): Promise<SavedDriveDetailResponse> {
  return requestJson<SavedDriveDetailResponse>(SAVED_DRIVES_ROUTE_PATHS.detail(driveId), {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
}

/**
 * Delete a saved drive by ID.
 */
export async function deleteSavedDrive(
  driveId: string,
  token?: string,
): Promise<DeleteSavedDriveResponse> {
  return requestJson<DeleteSavedDriveResponse>(SAVED_DRIVES_ROUTE_PATHS.detail(driveId), {
    method: 'DELETE',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
  });
}
