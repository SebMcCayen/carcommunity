/**
 * useNotifications — hook for the current user's in-app notification inbox.
 *
 * Privacy rules:
 *  - Only fetches the current user's notifications (enforced by backend).
 *  - Notification data is cleared on unmount (e.g. logout).
 *  - Tokens are never exposed in state or logs.
 *  - Protected notification details are loaded from the backend after navigating.
 *  - Backend is the sole authority for notification eligibility and content.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import { useI18n } from './useI18n';

import type { NotificationSummary } from '@carcommunity/shared/notifications';
import { DEFAULT_NOTIFICATION_PAGE_SIZE } from '@carcommunity/shared/notifications';

import {
  listNotifications,
  markNotificationRead,
  markAllNotificationsRead,
} from '../api/notifications';
import { useI18n } from '../hooks/useI18n';
import { loadSessionToken } from '../storage/tokenStorage';

export interface UseNotificationsResult {
  notifications: NotificationSummary[];
  unreadCount: number;
  isLoading: boolean;
  error: string | null;
  hasMore: boolean;
  currentPage: number;
  refresh: () => Promise<void>;
  loadMore: () => Promise<void>;
  markRead: (notificationId: string) => Promise<void>;
  markAllRead: () => Promise<void>;
}

const PAGE_SIZE = DEFAULT_NOTIFICATION_PAGE_SIZE;

/**
 * Fetches the current user's notification inbox from the backend.
 *
 * Clears notification data on unmount to protect private data after logout.
 */
export function useNotifications(): UseNotificationsResult {
  const { t } = useI18n();
  const [notifications, setNotifications] = useState<NotificationSummary[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [currentPage, setCurrentPage] = useState(1);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear private notification data on unmount (e.g. logout).
      setNotifications([]);
      setUnreadCount(0);
      setError(null);
    };
  }, []);

  const load = useCallback(async (page: number, append: boolean) => {
    setIsLoading(true);
    setError(null);
    try {
      const stored = await loadSessionToken().catch(() => null);
      const token = stored?.token ?? undefined;

      const res = await listNotifications(token, page, PAGE_SIZE);

      if (!mountedRef.current) return;

      setNotifications((prev) =>
        append ? [...prev, ...res.data.notifications] : res.data.notifications,
      );
      setUnreadCount(res.data.unreadCount);
      setHasMore(res.meta.hasNext);
      setCurrentPage(page);
    } catch {
      if (!mountedRef.current) return;
      setError(t('notifications.loadError'));
    } finally {
      if (mountedRef.current) {
        setIsLoading(false);
      }
    }
  }
  }, [t]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void load(1, false);
  }, [load]);

  const refresh = useCallback(async () => {
    await load(1, false);
  }, [load]);

  const loadMore = useCallback(async () => {
    if (!hasMore || isLoading) return;
    await load(currentPage + 1, true);
  }, [hasMore, isLoading, currentPage, load]);

  const markRead = useCallback(async (notificationId: string) => {
    try {
      const stored = await loadSessionToken().catch(() => null);
      const token = stored?.token ?? undefined;
      await markNotificationRead(notificationId, token);
      if (!mountedRef.current) return;
      setNotifications((prev) =>
        prev.map((n) =>
          n.notificationId === notificationId
            ? { ...n, readAt: new Date().toISOString() }
            : n,
        ),
      );
      setUnreadCount((prev) => Math.max(0, prev - 1));
    } catch {
      // Non-fatal; the user can retry by refreshing.
    }
  }, []);

  const markAllRead = useCallback(async () => {
    try {
      const stored = await loadSessionToken().catch(() => null);
      const token = stored?.token ?? undefined;
      await markAllNotificationsRead(token);
      if (!mountedRef.current) return;
      const now = new Date().toISOString();
      setNotifications((prev) => prev.map((n) => ({ ...n, readAt: n.readAt ?? now })));
      setUnreadCount(0);
    } catch {
      // Non-fatal.
    }
  }, []);

  return {
    notifications,
    unreadCount,
    isLoading,
    error,
    hasMore,
    currentPage,
    refresh,
    loadMore,
    markRead,
    markAllRead,
  };
}
