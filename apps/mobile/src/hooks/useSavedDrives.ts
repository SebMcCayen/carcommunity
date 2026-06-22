import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  SavedDriveDetail,
  SavedDriveListItem,
} from '@carcommunity/shared/saved-drives';

import {
  deleteSavedDrive as deleteSavedDriveApi,
  getSavedDrive as getSavedDriveApi,
  listSavedDrives,
} from '../api/saved-drives';
import { loadSessionToken } from '../storage/tokenStorage';

export type SavedDrivesLoadState = 'idle' | 'loading' | 'error';

export interface UseSavedDrivesResult {
  drives: SavedDriveListItem[];
  isLoading: boolean;
  error: string | null;
  hasNext: boolean;
  page: number;
  loadMore: () => Promise<void>;
  refresh: () => Promise<void>;
  deleteDrive: (driveId: string) => Promise<void>;
}

export interface UseSavedDriveDetailResult {
  drive: SavedDriveDetail | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

/**
 * Hook for the authenticated user's saved drives list.
 * Supports pagination and deletion.
 * Clears state when the component unmounts (e.g. on logout or entitlement loss).
 */
export function useSavedDrives(): UseSavedDrivesResult {
  const [drives, setDrives] = useState<SavedDriveListItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [hasNext, setHasNext] = useState(false);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear protected data on unmount (e.g. logout or entitlement loss).
      setDrives([]);
    };
  }, []);

  const loadPage = useCallback(async (pageNum: number, replace: boolean) => {
    setIsLoading(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await listSavedDrives(pageNum, 20, auth?.token ?? undefined);
      if (!mountedRef.current) return;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
      setDrives((prev) => (replace ? res.data.drives : [...prev, ...res.data.drives]));
      setPage(pageNum);
      setHasNext(res.meta.hasNext);
    } catch {
      if (!mountedRef.current) return;
      setError('savedDrives.error');
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadPage(1, true);
  }, [loadPage]);

  const loadMore = useCallback(async () => {
    if (isLoading || !hasNext) return;
    await loadPage(page + 1, false);
  }, [isLoading, hasNext, page, loadPage]);

  const refresh = useCallback(async () => {
    await loadPage(1, true);
  }, [loadPage]);

  const deleteDrive = useCallback(
    async (driveId: string) => {
      try {
        const auth = await loadSessionToken().catch(() => null);
        await deleteSavedDriveApi(driveId, auth?.token ?? undefined);
        if (!mountedRef.current) return;
        setDrives((prev) => prev.filter((d) => d.id !== driveId));
      } catch {
        if (!mountedRef.current) return;
        setError('savedDrives.deleteError');
      }
    },
    [],
  );

  return { drives, isLoading, error, hasNext, page, loadMore, refresh, deleteDrive };
}

/**
 * Hook for a single saved drive detail.
 * Clears state on unmount to protect sensitive route data.
 */
export function useSavedDriveDetail(driveId: string): UseSavedDriveDetailResult {
  const [drive, setDrive] = useState<SavedDriveDetail | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear protected route overview data on unmount.
      setDrive(null);
    };
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await getSavedDriveApi(driveId, auth?.token ?? undefined);
      if (!mountedRef.current) return;
      // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
      setDrive(res.data.drive);
    } catch {
      if (!mountedRef.current) return;
      setError('savedDrives.errorDetail');
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, [driveId]);

  useEffect(() => {
    void load();
  }, [load]);

  return { drive, isLoading, error, refresh: load };
}
