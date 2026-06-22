/**
 * Shared contracts for saved drives.
 *
 * Privacy rules encoded here:
 *  - No top speed, speed rankings, or risky-driving incentives.
 *  - Approximate areas only — never exact start or end addresses.
 *  - Route overview is member-only and optional.
 *  - Raw temporary route points are never exposed in normal API responses.
 *  - Saved drives belong only to the authenticated user.
 *
 * Architecture note:
 *  The current implementation is summary-only. Route overview requires a
 *  short-lived TemporaryDrivePoint buffer that is not yet implemented.
 *  TODO: Implement TemporaryDrivePoint collection and route overview once
 *        the temporary route buffer architecture is confirmed safe and small.
 */

export const SAVED_DRIVE_STATUSES = ['saved', 'discarded'] as const;
export type SavedDriveStatus = (typeof SAVED_DRIVE_STATUSES)[number];

export const SAVED_DRIVES_ROUTE_PATHS = {
  list: '/v1/saved-drives',
  detail: (driveId: string) => `/v1/saved-drives/${driveId}`,
  postDriveSummary: (sessionId: string) =>
    `/v1/live-location/sessions/${sessionId}/post-drive-summary`,
  saveDrive: (sessionId: string) =>
    `/v1/live-location/sessions/${sessionId}/save-drive`,
  discardDrive: (sessionId: string) =>
    `/v1/live-location/sessions/${sessionId}/discard-drive`,
} as const;

export const DEFAULT_SAVED_DRIVES_PAGE_SIZE = 20;
export const MAX_SAVED_DRIVES_PAGE_SIZE = 50;

/**
 * A minimized route overview point.
 * Contains only lat/lng — no speed, heading, altitude, or telemetry.
 * Never returned in list responses; only in member detail responses.
 */
export interface RouteOverviewPoint {
  latitude: number;
  longitude: number;
}

/**
 * A temporary drive summary computed after a live location session ends.
 * Not persisted automatically — the user must explicitly choose to save or discard.
 *
 * distanceMeters and averageSpeedMetersPerSecond are null in the summary-only
 * implementation because no route buffer is maintained.
 * TODO: Populate once TemporaryDrivePoint collection is implemented.
 */
export interface PostDriveSummary {
  sessionId: string;
  startedAt: string;
  endedAt: string;
  durationSeconds: number;
  /** Null in summary-only mode; populated once route buffer is available. */
  distanceMeters: number | null;
  /** Null in summary-only mode; populated once route buffer is available. */
  averageSpeedMetersPerSecond: number | null;
  /** Approximate area label; null if not available. Never an exact address. */
  approximateStartArea: string | null;
  /** Approximate area label; null if not available. Never an exact address. */
  approximateEndArea: string | null;
}

export interface SaveDriveRequest {
  /** Source live location session identifier. Must be owned by the authenticated user. */
  sessionId: string;
}

export interface SaveDriveResponse {
  ok: true;
  data: {
    drive: SavedDriveDetail;
  };
}

export interface DiscardDriveResponse {
  ok: true;
  data: {
    discarded: true;
    sessionId: string;
  };
}

/**
 * Summary item returned in the saved-drive list.
 * Never includes routeOverview to keep list responses small.
 */
export interface SavedDriveListItem {
  id: string;
  startedAt: string;
  endedAt: string;
  durationSeconds: number;
  distanceMeters: number | null;
  averageSpeedMetersPerSecond: number | null;
  approximateStartArea: string | null;
  approximateEndArea: string | null;
  createdAt: string;
}

/**
 * Full drive detail. routeOverview is only populated for member users.
 * Never includes raw temporary route points.
 * Never includes top speed or speed rankings.
 */
export interface SavedDriveDetail extends SavedDriveListItem {
  /**
   * Minimized route overview for eligible members only.
   * Null for free users or when route data is unavailable.
   * TODO: Populate once TemporaryDrivePoint collection is implemented.
   */
  routeOverview: RouteOverviewPoint[] | null;
}

export interface PaginatedSavedDrivesResponse {
  ok: true;
  data: {
    drives: SavedDriveListItem[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface SavedDriveDetailResponse {
  ok: true;
  data: {
    drive: SavedDriveDetail;
  };
}

export interface DeleteSavedDriveResponse {
  ok: true;
  data: {
    deleted: true;
    driveId: string;
  };
}

export interface PostDriveSummaryResponse {
  ok: true;
  data: {
    summary: PostDriveSummary;
    canSave: boolean;
  };
}
