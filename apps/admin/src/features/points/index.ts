/**
 * Points feature module for the admin portal (Phase 13 vertical).
 *
 * Reads come straight from Firestore (admin rules-gated since Phase 9g):
 * pointsLedger/{uid} for the balance and its `entries` subcollection for
 * the ledger. The adjustment mutation goes through the audited
 * `points-adminAdjust` callable. Exported signatures and response
 * envelope types are unchanged, so pages keep working — this module is
 * the adapter layer.
 *
 * Security notes (unchanged):
 *  - Backend is the sole authority for balances and ledger entries.
 *  - Clients never calculate or set an absolute balance.
 *  - A reason is required for every adjustment and is audited server-side.
 */

import { doc, getDoc } from 'firebase/firestore';
import {
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
  type AdminPointsAdjustmentRequest,
  type AdminPointsAdjustmentResponse,
} from '@carcommunity/shared/points';

import { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export type {
  PointsBalanceResponse,
  PaginatedPointsLedgerResponse,
  AdminPointsAdjustmentRequest,
  AdminPointsAdjustmentResponse,
};
export { ApiError };

/**
 * Returns the current Kronpoäng balance for a user (direct Firestore
 * read of pointsLedger/{uid}; a missing wallet is a zero balance).
 */
export async function getAdminUserPointsBalance(
  userId: string,
  _token?: string,
): Promise<PointsBalanceResponse> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'pointsLedger', userId));
  const balance = (snapshot.data()?.balance as number | undefined) ?? 0;
  return {
    ok: true,
    data: { balance, displayName: 'Kronpoäng', shortForm: 'KP' },
  };
}

/** Reads older ledger entries through a query-bound backend cursor. */
export async function getAdminUserPointsLedger(
  userId: string,
  _page?: number,
  _token?: string,
  cursor?: string | null,
): Promise<PaginatedPointsLedgerResponse> {
  return callAdmin<PaginatedPointsLedgerResponse>('admin-listPointsLedger', {
    targetUid: userId,
    cursor: cursor ?? null,
  });
}

interface PointsAdminCallableResponse {
  targetUid: string;
  entryId: string;
  amount: number;
  balanceAfter: number;
  alreadyApplied: boolean;
}

/**
 * Applies an admin adjustment via the audited points-adminAdjust
 * callable (positive integer amounts; debits that would overdraw are
 * rejected server-side).
 */
export async function applyAdminPointsAdjustment(
  userId: string,
  request: AdminPointsAdjustmentRequest,
  _token?: string,
): Promise<AdminPointsAdjustmentResponse> {
  const result = await callAdmin<PointsAdminCallableResponse>('points-adminAdjust', {
    targetUid: userId,
    type: request.type,
    amount: request.amount,
    reason: request.reason,
    ...(request.expectedActorUid ? { expectedActorUid: request.expectedActorUid } : {}),
    ...(request.idempotencyKey ? { idempotencyKey: request.idempotencyKey } : {}),
  });
  return {
    ok: true,
    data: {
      transactionId: result.entryId,
      transactionType: request.type,
      amount: result.amount,
      balanceAfter: result.balanceAfter,
      createdAt: new Date().toISOString(),
    },
  };
}
