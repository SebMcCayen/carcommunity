/**
 * useGroupDriveMarkers — safe polling hook for group drive live location markers.
 *
 * Polls GET /v1/events/:eventId/group-drive/markers on a conservative interval
 * while the group map screen is visible, the app is in the foreground, the user
 * is authenticated, eligible, and an active group participant.
 *
 * Polling contract:
 *  - Polls only while the app is in the foreground (AppState === 'active').
 *  - Polls only while isEligible is true (member + active participant).
 *  - Stops polling when the component unmounts.
 *  - Prevents overlapping in-flight requests via an in-flight guard ref.
 *  - Applies bounded exponential backoff after consecutive network failures.
 *  - Clears markers immediately when access is lost (401/403).
 *  - Resumes polling on foreground return.
 *
 * Privacy:
 *  - Marker state is held in transient React state; never written to
 *    SecureStore, AsyncStorage, or any other persistent store.
 *  - Coordinates are never logged.
 *  - Session tokens are never logged or persisted by this hook.
 *  - Participant IDs in markers are opaque and do not map to user IDs.
 *
 * Safe driving:
 *  - Polling continues regardless of driving state (map data must stay fresh).
 *  - UI interaction with the map is governed by the GroupDriveScreen component.
 */

import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS } from '@carcommunity/shared/live-location';
import type { GroupDriveMarker } from '@carcommunity/shared/group-drive';

import { GroupDriveApiError, loadGroupDriveMarkers } from '../api/group-drive';
import { useAuth } from './useAuth';

/**
 * Conservative polling interval in milliseconds.
 * Approximately every 12 seconds while eligible and in foreground.
 */
const POLL_INTERVAL_MS = 12_000;

const INITIAL_RETRY_DELAY_MS = 5_000;
const MAX_RETRY_DELAY_MS = 60_000;

export interface UseGroupDriveMarkersOptions {
  eventId: string;
  /**
   * Whether the current user is eligible to view markers.
   * Requires: authentication + member_monthly + active group participant.
   * This is a client-side UX hint only — the backend enforces access independently.
   */
  isEligible: boolean;
}

export interface UseGroupDriveMarkersResult {
  /** Live location markers for active group drive participants. Never includes the viewer's own marker. */
  markers: GroupDriveMarker[];
  /** True while the current marker fetch is in-flight. */
  isLoading: boolean;
}

/**
 * Returns group drive live location markers and manages the polling lifecycle.
 *
 * Only one instance of this hook should be active per screen to avoid duplicate polling.
 * Stop using this hook (or set isEligible=false) when the user leaves the group drive.
 */
export function useGroupDriveMarkers({
  eventId,
  isEligible,
}: UseGroupDriveMarkersOptions): UseGroupDriveMarkersResult {
  const { withToken } = useAuth();
  const [markers, setMarkers] = useState<GroupDriveMarker[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const mountedRef = useRef(true);
  const isRequestInFlightRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // Clear markers immediately when eligibility is lost
  useEffect(() => {
    if (!isEligible) {
      if (mountedRef.current) {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- security: clear marker data as soon as access is lost
        setMarkers([]);
      }
    }
  }, [isEligible]);

  useEffect(() => {
    if (!isEligible) {
      return;
    }

    let polling = true;
    let consecutiveFailures = 0;
    let nextAllowedPollAt = 0;

    async function poll(): Promise<void> {
      if (!polling) return;
      if (AppState.currentState !== 'active') return;
      if (isRequestInFlightRef.current) return;
      if (Date.now() < nextAllowedPollAt) return;

      isRequestInFlightRef.current = true;
      if (mountedRef.current) setIsLoading(true);

      try {
        const response = await withToken((token) => loadGroupDriveMarkers(eventId, token));

        if (!polling) return;

        if (response === null) {
          // No auth token available — clear markers and stop polling this cycle
          if (mountedRef.current) setMarkers([]);
          polling = false;
          return;
        }

        const now = Date.now();
        // Client-side stale filter as additional safety net (backend filters too).
        // Do not log coordinates.
        const freshMarkers = response.data.markers.filter((m) => {
          const recordedAt = new Date(m.coordinate.recordedAt).getTime();
          return now - recordedAt < LIVE_LOCATION_MARKER_STALE_THRESHOLD_MS;
        });

        if (mountedRef.current) setMarkers(freshMarkers);
        consecutiveFailures = 0;
        nextAllowedPollAt = 0;
      } catch (error) {
        if (!polling) return;

        if (error instanceof GroupDriveApiError) {
          if (error.statusCode === 401 || error.statusCode === 403) {
            // Access lost — clear markers and stop polling until eligibility changes
            if (mountedRef.current) setMarkers([]);
            polling = false;
            return;
          }
        }

        // Network or server failure — apply bounded exponential back-off
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

    // Initial fetch
    if (AppState.currentState === 'active') {
      void poll();
    }

    // Scheduled polling interval
    const intervalId = setInterval(() => {
      if (AppState.currentState === 'active' && polling) {
        void poll();
      }
    }, POLL_INTERVAL_MS);

    // Resume polling immediately on foreground return
    const appStateSub = AppState.addEventListener('change', (nextState) => {
      if (nextState === 'active' && polling) {
        void poll();
      }
    });

    return () => {
      polling = false;
      clearInterval(intervalId);
      appStateSub.remove();
    };
  }, [eventId, isEligible, withToken]);

  return {
    markers: isEligible ? markers : [],
    isLoading,
  };
}
