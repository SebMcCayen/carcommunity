/**
 * usePoints — hook for the current user's Kronpoäng (KP) wallet.
 *
 * Privacy rules:
 *  - Only fetches the current user's balance and transactions (enforced by the backend).
 *  - Wallet data is cleared on unmount (e.g. logout or screen exit).
 *  - Tokens are never exposed in state or logs.
 *  - Backend balance is used as authoritative — never sum paginated transactions locally.
 *  - No purchase, transfer, withdrawal, or ranking controls.
 *  - No client-side point awarding.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type { PointsTransactionSummary } from '@carcommunity/shared/points';
import { MAX_POINTS_PAGE_SIZE } from '@carcommunity/shared/points';

import { getPointsBalance, getPointsLedger } from '../api/points';
import { loadSessionToken } from '../storage/tokenStorage';

export interface UsePointsResult {
  /** Authoritative KP balance from the backend. Use this — do not sum transactions. */
  balance: number;
  transactions: PointsTransactionSummary[];
  isLoading: boolean;
  error: string | null;
  hasMore: boolean;
  currentPage: number;
  refresh: () => Promise<void>;
  loadMore: () => Promise<void>;
}

const PAGE_SIZE = MAX_POINTS_PAGE_SIZE;

/**
 * Fetches the current user's Kronpoäng balance and recent ledger from the backend.
 *
 * Uses the backend balance response as authoritative.
 * Never sums paginated transactions to derive a balance.
 * Clears wallet data on unmount to protect private data after logout.
 */
export function usePoints(): UsePointsResult {
  const [balance, setBalance] = useState(0);
  const [transactions, setTransactions] = useState<PointsTransactionSummary[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear private wallet data on unmount (e.g. logout).
      setBalance(0);
      setTransactions([]);
      setError(null);
    };
  }, []);

  const load = useCallback(async (page: number, append: boolean) => {
    setIsLoading(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const token = auth?.token ?? undefined;

      if (page === 1) {
        // Fetch balance and first page of ledger together.
        const [balanceRes, ledgerRes] = await Promise.all([
          getPointsBalance(token),
          getPointsLedger(token, 1, PAGE_SIZE),
        ]);

        if (!mountedRef.current) return;

        // Use the authoritative backend balance — never compute locally.
        setBalance(balanceRes.data.balance);
        setTransactions(ledgerRes.data.transactions);
        setHasMore(ledgerRes.meta.hasNext);
        setCurrentPage(1);
      } else {
        const ledgerRes = await getPointsLedger(token, page, PAGE_SIZE);

        if (!mountedRef.current) return;

        // Update authoritative balance from ledger response.
        setBalance(ledgerRes.data.balance);
        if (append) {
          setTransactions((prev) => [...prev, ...ledgerRes.data.transactions]);
        } else {
          setTransactions(ledgerRes.data.transactions);
        }
        setHasMore(ledgerRes.meta.hasNext);
        setCurrentPage(page);
      }
    } catch {
      if (!mountedRef.current) return;
      setError('points.error');
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  const refresh = useCallback(async () => {
    await load(1, false);
  }, [load]);

  const loadMore = useCallback(async () => {
    if (isLoading || !hasMore) return;
    await load(currentPage + 1, true);
  }, [load, isLoading, hasMore, currentPage]);

  useEffect(() => {
    // `load` is stable (useCallback with empty deps), so this effect runs once on mount.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void load(1, false);
  }, [load]);

  return { balance, transactions, isLoading, error, hasMore, currentPage, refresh, loadMore };
}
