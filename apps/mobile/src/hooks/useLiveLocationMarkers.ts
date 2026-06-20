/**
 * useLiveLocationMarkers — safe polling hook for live location markers.
 *
 * Polls GET /v1/live-location/markers on a conservative interval while the
 * component is mounted, the app is in the foreground, the user is
 * authenticated, and the user has an active member_monthly subscription.
 *
 * Polling contract:
 *  - Polls only while the app is in the foreground (AppState === 'active').
 *  - Polls only while the user is authenticated and eligible (member_monthly).
 *  - Stops polling when the component unmounts.
 *  - Prevents overlapping in-flight requests via an in-flight guard ref.
 *  - Applies bounded exponential backoff after consecutive network failures.
 *  - Clears markers immediately when access is lost (401/403) or the
 *    feature is disabled by the backend.
 *  - Resumes polling on foreground return.
 *
 * Privacy:
 *  - Marker state is held in transient React state; never written to
 *    SecureStore, AsyncStorage, or any other persistent store.
 *  - Coordinates are never logged.
 *  - Session tokens are never logged or persisted by this hook.
 *  - Client-side stale filtering is applied as an additional safety net
 *    on top of backend filtering.
 *
 * TODO: Implement user-blocking / visibility filtering once the blocking
 *   graph is available on the backend. Until then all visible markers
 *   returned by the backend are shown to eligible members.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';

import {
  DEFAULT_LIVE_LOCATION_PAGE_SIZE,
  LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS,
} from '@carcommunity/shared/live-location';
import { canViewOtherLiveLocations } from '@carcommunity/shared/users';

import { loadLiveLocationMarkers, LiveLocationApiError } from '../api/live-location';
import type { MapMarkerViewModel } from '../map/types';
import { useAuth } from './useAuth';

/**
 * Conservative polling interval in milliseconds.
 * Approximately every 12 seconds while eligible and in foreground.
 */
const POLL_INTERVAL_MS = 12_000;

/**
 * Initial retry delay after a network/server failure.
 * Each consecutive failure doubles the delay up to MAX_RETRY_DELAY_MS.
 */
const INITIAL_RETRY_DELAY_MS = 5_000;

/** Upper bound on retry back-off delay. */
const MAX_RETRY_DELAY_MS = 60_000;

export interface UseLiveLocationMarkersResult {
  /** Live location markers for other active members. Never includes the current user's own marker. */
  markers: MapMarkerViewModel[];
  /** True while the current marker fetch is in-flight. */
  isLoading: boolean;
  /**
   * True when the current user is eligible to view other members' live markers.
   * Eligibility requires authentication and an active member_monthly subscription.
   * This is a client-side UX hint only — the backend enforces access independently.
   */
  isMemberEligible: boolean;
}

/**
 * Returns live location markers for other active members and manages the polling lifecycle.
 *
 * Only one instance of this hook should be active per screen to avoid duplicate polling.
 */
export function useLiveLocationMarkers(): UseLiveLocationMarkersResult {
  const { currentUser, isAuthenticated, withToken } = useAuth();
  const [markers, setMarkers] = useState<MapMarkerViewModel[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  /** Prevents state updates after the component has unmounted. */
  const mountedRef = useRef(true);

  /** Guards against overlapping in-flight requests. */
  const isRequestInFlightRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const isMemberEligible = useMemo(() => {
    if (!isAuthenticated || currentUser === null) return false;
    // AuthenticatedUserSummary.roles is an array; use the primary role (first entry).
    // Backend remains the source of truth — this check is for UX gating only.
    const primaryRole = currentUser.roles[0] ?? 'user';
    return canViewOtherLiveLocations({
      role: primaryRole,
      status: currentUser.status,
      subscriptionEntitlement: currentUser.subscriptionEntitlement,
    });
  }, [isAuthenticated, currentUser]);

  useEffect(() => {
    if (!isMemberEligible) {
      setMarkers([]);
      return;
    }

    /**
     * Local polling flag. Setting this to false stops the current poll cycle
     * without affecting future effect runs (e.g. when isMemberEligible changes).
     */
    let polling = true;
    let consecutiveFailures = 0;
    let nextAllowedPollAt = 0;

    async function poll(): Promise<void> {
      if (!polling) return;
      if (isRequestInFlightRef.current) return;
      if (Date.now() < nextAllowedPollAt) return;

      isRequestInFlightRef.current = true;
      if (mountedRef.current) setIsLoading(true);

      try {
        const response = await withToken((token) =>
          loadLiveLocationMarkers(1, DEFAULT_LIVE_LOCATION_PAGE_SIZE, token),
        );

        if (!polling) return;

        if (response === null) {
          // No auth token available — clear markers and stop polling this cycle.
          if (mountedRef.current) setMarkers([]);
          polling = false;
          return;
        }

        // Client-side stale filter as an additional safety net.
        // Do not log coordinates.
        const now = Date.now();
        const freshMarkers: MapMarkerViewModel[] = response.data.markers
          .filter((m) => {
            const recordedAt = new Date(m.coordinate.recordedAt).getTime();
            return now - recordedAt < LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS;
          })
          .map((m) => ({
            id: m.sessionId,
            coordinate: {
              latitude: m.coordinate.latitude,
              longitude: m.coordinate.longitude,
            },
            type: 'member' as const,
          }));

        if (mountedRef.current) setMarkers(freshMarkers);
        consecutiveFailures = 0;
        nextAllowedPollAt = 0;
      } catch (error) {
        if (!polling) return;

        if (error instanceof LiveLocationApiError) {
          if (error.statusCode === 401 || error.statusCode === 403) {
            // Access lost or feature disabled — clear markers and stop polling
            // until the component remounts or the user's eligibility changes.
            if (mountedRef.current) setMarkers([]);
            polling = false;
            return;
          }
        }

        // Network or server failure — apply bounded exponential back-off.
        consecutiveFailures += 1;
        const delay = Math.min(
          INITIAL_RETRY_DELAY_MS * Math.pow(2, consecutiveFailures - 1),
          MAX_RETRY_DELAY_MS,
        );
        nextAllowedPollAt = Date.now() + delay;
      } finally {
        isRequestInFlightRef.current = false;
        if (mountedRef.current) setIsLoading(false);
      }
    }

    // Initial fetch.
    void poll();

    // Scheduled polling interval — skips polls when app is backgrounded.
    const intervalId = setInterval(() => {
      if (AppState.currentState === 'active' && polling) {
        void poll();
      }
    }, POLL_INTERVAL_MS);

    // Resume polling immediately on foreground return.
    const appStateSub = AppState.addEventListener('change', (nextState) => {
      if (nextState === 'active' && polling) {
        void poll();
      }
    });

    return () => {
      polling = false;
      clearInterval(intervalId);
      appStateSub.remove();
      if (mountedRef.current) {
        setMarkers([]);
        setIsLoading(false);
      }
    };
  }, [isMemberEligible, withToken]);

  return { markers, isLoading, isMemberEligible };
}
