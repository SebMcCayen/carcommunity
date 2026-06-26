/**
 * Points feature module for the admin portal.
 *
 * Provides API client functions for the Kronpoäng (KP) points admin area.
 * Pages in src/app/users/[id]/ import from here for points operations.
 *
 * Security notes:
 *  - All operations are validated server-side. Client-side role checks are
 *    UX only and are NOT security boundaries.
 *  - Backend is the sole authority for point balances and ledger entries.
 *  - Clients must never calculate or set an absolute balance.
 *  - Only positive integer amounts are accepted for adjustments.
 *  - A reason is required for every admin adjustment and is audited server-side.
 *  - Debits that would produce a negative balance are rejected by the backend.
 *  - Existing ledger entries cannot be edited or deleted through any API.
 *  - No transfer, purchase, withdrawal, or cash-value operations are exposed.
 *  - No public leaderboards or other users' balances are exposed.
 *  - Do not include personal data beyond the userId in requests.
 */

import {
  buildAdminPointsAdjustPath,
  buildAdminUserPointsBalancePath,
  buildAdminUserPointsLedgerPath,
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
  type AdminPointsAdjustmentRequest,
  type AdminPointsAdjustmentResponse,
} from '@carcommunity/shared/points';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  PointsBalanceResponse,
  PaginatedPointsLedgerResponse,
  AdminPointsAdjustmentRequest,
  AdminPointsAdjustmentResponse,
};
export { ApiError };

// ---------------------------------------------------------------------------
// API client helpers
// ---------------------------------------------------------------------------

/**
 * Returns the current Kronpoäng balance for a user.
 * Requires admin or owner role (enforced server-side).
 *
 * NOTE: The balance is read from the backend and must not be modified
 * or recalculated on the client.
 */
export async function getAdminUserPointsBalance(
  userId: string,
  token?: string,
): Promise<PointsBalanceResponse> {
  return apiRequest<PointsBalanceResponse>(buildAdminUserPointsBalancePath(userId), { token });
}

/**
 * Returns a paginated ledger for a user.
 * Requires admin or owner role (enforced server-side).
 */
export async function getAdminUserPointsLedger(
  userId: string,
  page?: number,
  token?: string,
): Promise<PaginatedPointsLedgerResponse> {
  const url = page
    ? `${buildAdminUserPointsLedgerPath(userId)}?page=${page}`
    : buildAdminUserPointsLedgerPath(userId);
  return apiRequest<PaginatedPointsLedgerResponse>(url, { token });
}

/**
 * Applies an admin adjustment (credit or debit) to a user's Kronpoäng balance.
 * Requires admin or owner role (enforced server-side).
 *
 * Constraints (all enforced server-side):
 *  - amount must be a positive integer (1 – 100 000).
 *  - reason is required.
 *  - debits that would produce a negative balance are rejected.
 *  - the resulting balance is calculated by the backend — never the client.
 *  - the action is written to the audit log server-side.
 */
export async function applyAdminPointsAdjustment(
  userId: string,
  request: AdminPointsAdjustmentRequest,
  token?: string,
): Promise<AdminPointsAdjustmentResponse> {
  return apiRequest<AdminPointsAdjustmentResponse>(buildAdminPointsAdjustPath(userId), {
    method: 'POST',
    body: request,
    token,
  });
}
