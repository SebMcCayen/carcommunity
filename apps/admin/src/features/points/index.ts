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

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  type Timestamp,
} from 'firebase/firestore';
import {
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
  type AdminPointsAdjustmentRequest,
  type AdminPointsAdjustmentResponse,
  type PointsTransactionSummary,
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

const LEDGER_PAGE_SIZE = 20;

interface LedgerEntryDoc {
  transactionType: PointsTransactionSummary['transactionType'];
  source: PointsTransactionSummary['source'];
  amount: number;
  balanceAfter: number;
  description: string;
  createdAt: Timestamp;
}

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

/**
 * Returns the most recent ledger entries for a user, newest first.
 * Firestore cursor pagination replaces the legacy page numbers; this
 * adapter serves the first page (deeper history lands with the full
 * admin ledger view in the Phase 13 checklist).
 */
export async function getAdminUserPointsLedger(
  userId: string,
  _page?: number,
  _token?: string,
): Promise<PaginatedPointsLedgerResponse> {
  const db = getAdminFirestore();
  const [walletSnap, entriesSnap] = await Promise.all([
    getDoc(doc(db, 'pointsLedger', userId)),
    getDocs(
      query(
        collection(db, 'pointsLedger', userId, 'entries'),
        orderBy('createdAt', 'desc'),
        limit(LEDGER_PAGE_SIZE),
      ),
    ),
  ]);

  const transactions: PointsTransactionSummary[] = entriesSnap.docs.map((entry) => {
    const data = entry.data() as LedgerEntryDoc;
    return {
      transactionId: entry.id,
      transactionType: data.transactionType,
      source: data.source,
      amount: data.amount,
      balanceAfter: data.balanceAfter,
      description: data.description,
      createdAt: data.createdAt.toDate().toISOString(),
    };
  });

  return {
    ok: true,
    data: {
      balance: (walletSnap.data()?.balance as number | undefined) ?? 0,
      transactions,
    },
    meta: {
      page: 1,
      pageSize: LEDGER_PAGE_SIZE,
      total: transactions.length,
      hasNext: transactions.length === LEDGER_PAGE_SIZE,
    },
  };
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
