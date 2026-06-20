import {
  BLOCKING_ROUTE_PATHS,
  DEFAULT_BLOCKED_USERS_PAGE_SIZE,
  type BlockUserResponse,
  type BlockedUsersListResponse,
  type UnblockUserResponse,
} from '@carcommunity/shared/blocking';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { authorization: 'Bearer ' + token } : {};

/**
 * Typed error thrown when a blocking API request returns a non-2xx HTTP status.
 */
export class BlockingApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'BlockingApiError';
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
    throw new BlockingApiError(response.status, `Blocking request failed with status ${response.status}`);
  }

  return (await response.json()) as TResponse;
}

/**
 * Block a user by their ID.
 * The response includes `shouldRefreshMarkers: true` to signal the client
 * to clear or refresh its cached live location marker data.
 *
 * Backend enforces all blocking rules. Client-side blocking is not a security boundary.
 */
export async function blockUser(
  targetUserId: string,
  token?: string,
): Promise<BlockUserResponse> {
  return requestJson<BlockUserResponse>(BLOCKING_ROUTE_PATHS.userBlock(targetUserId), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify({}),
  });
}

/**
 * Unblock a previously blocked user.
 * Idempotent: returns `{ unblocked: false }` if no block existed.
 */
export async function unblockUser(
  targetUserId: string,
  token?: string,
): Promise<UnblockUserResponse> {
  return requestJson<UnblockUserResponse>(BLOCKING_ROUTE_PATHS.userBlock(targetUserId), {
    method: 'DELETE',
    headers: buildAuthHeader(token),
  });
}

/**
 * Fetch the paginated list of users blocked by the current user.
 * Does NOT include users who have blocked the current user —
 * that information is intentionally withheld to protect privacy.
 */
export async function listBlockedUsers(
  page = 1,
  pageSize = DEFAULT_BLOCKED_USERS_PAGE_SIZE,
  token?: string,
): Promise<BlockedUsersListResponse> {
  const searchParams = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  });

  return requestJson<BlockedUsersListResponse>(
    `${BLOCKING_ROUTE_PATHS.myBlockedUsers}?${searchParams.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}
