/**
 * useBadges — hook for the current user's awarded badges.
 *
 * Privacy rules:
 *  - Only fetches the current user's badges (enforced by the backend).
 *  - Badge data is cleared on unmount (e.g. logout or screen exit).
 *  - Tokens are never exposed in state or logs.
 *  - No badge awarding from the client — backend is the sole authority.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type { AwardedBadge } from '@carcommunity/shared/badges';

import { getCurrentUserBadges } from '../api/badges';
import { loadSessionToken } from '../storage/tokenStorage';

export interface UseBadgesResult {
  badges: AwardedBadge[];
  isLoading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  /** Key of a newly awarded badge to show a one-time in-app notice. Null when none. */
  newBadgeKey: string | null;
  /** Dismiss the new-badge notice. */
  dismissNewBadge: () => void;
}

/**
 * Fetches the current user's badges from the backend.
 * Clears badge state on unmount to protect private data after logout.
 *
 * newBadgeKey reflects the most recently added badge compared to the
 * previously loaded list, enabling a non-blocking in-app notice.
 * The same badge is never shown again within the same mount cycle.
 */
export function useBadges(): UseBadgesResult {
  const [badges, setBadges] = useState<AwardedBadge[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [newBadgeKey, setNewBadgeKey] = useState<string | null>(null);
  const mountedRef = useRef(true);
  const previousKeysRef = useRef<Set<string>>(new Set());
  const shownKeysRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // Clear private badge data on unmount (e.g. logout).
      setBadges([]);
      setNewBadgeKey(null);
    };
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const auth = await loadSessionToken().catch(() => null);
      const res = await getCurrentUserBadges(auth?.token ?? undefined);
      if (!mountedRef.current) return;

      const newKeys = new Set(res.data.badges.map((b) => b.key));

      // Detect a newly awarded badge that wasn't in the previous load.
      // Do not re-show a badge notification that was already shown.
      let detectedNew: string | null = null;
      for (const key of newKeys) {
        if (!previousKeysRef.current.has(key) && !shownKeysRef.current.has(key)) {
          detectedNew = key;
          shownKeysRef.current.add(key);
          break;
        }
      }

      previousKeysRef.current = newKeys;
      setBadges(res.data.badges);
      if (detectedNew) {
        setNewBadgeKey(detectedNew);
      }
    } catch {
      if (!mountedRef.current) return;
      setError('badges.error');
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const dismissNewBadge = useCallback(() => {
    setNewBadgeKey(null);
  }, []);

  return { badges, isLoading, error, refresh: load, newBadgeKey, dismissNewBadge };
}
