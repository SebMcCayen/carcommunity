/**
 * Crown Hunt (Kronjakt) feature module for the admin portal.
 *
 * Provides API client functions for managing Kronjakt points and reviewing claims.
 *
 * Security notes:
 *  - Backend is the sole authority for eligibility, claims, and Kronpoäng awards.
 *  - All operations are validated and authorised server-side.
 *  - New points start as draft — activation requires explicit admin confirmation.
 *  - Important changes are audited server-side.
 *  - No exact user claim coordinates are exposed.
 *  - No user routes or movement history are exposed.
 *  - High-risk claims are shown for review only — no manual point award in this step.
 *  - Do not hard-delete active or previously claimed points; prefer pause/end.
 *  - Kronjakt must never encourage speeding, risky driving, or unsafe stops.
 */

import {
  CROWN_HUNT_ROUTE_PATHS,
  buildAdminCrownHuntPointPath,
  buildAdminCrownHuntActivatePath,
  buildAdminCrownHuntPausePath,
  type AdminActivateCrownHuntPointRequest,
  type AdminCreateCrownHuntPointRequest,
  type AdminUpdateCrownHuntPointRequest,
  type AdminCrownHuntPointResponse,
  type AdminCrownHuntPointSummary,
  type AdminCrownHuntClaimSummary,
  type CrownHuntClaimResult,
  type CrownHuntPointStatus,
  type PaginatedAdminCrownHuntPointsResponse,
  type PaginatedAdminCrownHuntClaimsResponse,
} from '@carcommunity/shared/crown-hunt';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  AdminCreateCrownHuntPointRequest,
  AdminUpdateCrownHuntPointRequest,
  AdminCrownHuntPointSummary,
  AdminCrownHuntClaimSummary,
  CrownHuntClaimResult,
  CrownHuntPointStatus,
  PaginatedAdminCrownHuntPointsResponse,
  PaginatedAdminCrownHuntClaimsResponse,
};
export { ApiError };

// ---------------------------------------------------------------------------
// API functions
// ---------------------------------------------------------------------------

/**
 * Lists all Kronjakt points (all statuses) for the admin view.
 * Requires admin or owner role (enforced server-side).
 */
export async function adminListCrownHuntPoints(
  page = 1,
  token?: string,
): Promise<PaginatedAdminCrownHuntPointsResponse> {
  return apiRequest<PaginatedAdminCrownHuntPointsResponse>(
    `${CROWN_HUNT_ROUTE_PATHS.adminPoints}?page=${page}`,
    { token },
  );
}

/**
 * Creates a new Kronjakt point in draft status.
 * Backend enforces: draft status on creation, safety validation, approval requirements.
 */
export async function adminCreateCrownHuntPoint(
  request: AdminCreateCrownHuntPointRequest,
  token?: string,
): Promise<AdminCrownHuntPointResponse> {
  return apiRequest<AdminCrownHuntPointResponse>(CROWN_HUNT_ROUTE_PATHS.adminPoints, {
    method: 'POST',
    body: request,
    token,
  });
}

/**
 * Updates an existing draft or paused Kronjakt point.
 * Active points cannot be edited; pause first.
 */
export async function adminUpdateCrownHuntPoint(
  pointId: string,
  request: AdminUpdateCrownHuntPointRequest,
  token?: string,
): Promise<AdminCrownHuntPointResponse> {
  return apiRequest<AdminCrownHuntPointResponse>(buildAdminCrownHuntPointPath(pointId), {
    method: 'PATCH',
    body: request,
    token,
  });
}

/**
 * Activates a draft Kronjakt point.
 * Requires a safety confirmation note.
 * Backend enforces approval requirements and writes an audit entry.
 */
export async function adminActivateCrownHuntPoint(
  pointId: string,
  approvalNote: string,
  token?: string,
): Promise<AdminCrownHuntPointResponse> {
  return apiRequest<AdminCrownHuntPointResponse>(buildAdminCrownHuntActivatePath(pointId), {
    method: 'POST',
    body: { safeLocationConfirmed: true, approvalNote } satisfies AdminActivateCrownHuntPointRequest,
    token,
  });
}

/**
 * Pauses an active Kronjakt point.
 * Backend writes an audit entry.
 */
export async function adminPauseCrownHuntPoint(
  pointId: string,
  token?: string,
): Promise<AdminCrownHuntPointResponse> {
  return apiRequest<AdminCrownHuntPointResponse>(buildAdminCrownHuntPausePath(pointId), {
    method: 'POST',
    body: {},
    token,
  });
}

/**
 * Lists Kronjakt claims for admin review.
 * Result can be filtered to risk_review claims.
 * No exact user coordinates are included.
 */
export async function adminListCrownHuntClaims(
  page = 1,
  filterResult?: CrownHuntClaimResult,
  token?: string,
): Promise<PaginatedAdminCrownHuntClaimsResponse> {
  const params = new URLSearchParams({ page: String(page) });
  if (filterResult) params.set('result', filterResult);
  return apiRequest<PaginatedAdminCrownHuntClaimsResponse>(
    `${CROWN_HUNT_ROUTE_PATHS.adminClaims}?${params.toString()}`,
    { token },
  );
}
