import { useCallback, useEffect, useRef, useState } from 'react';

import * as ExpoLocation from 'expo-location';

import type {
  LiveLocationCoordinate,
  LiveLocationDuration,
} from '@carcommunity/shared/live-location';

import {
  hideMeNow as hideMeNowApi,
  startLiveLocationSession,
  stopLiveLocationSession,
  updateLiveLocationPosition,
} from '../api/live-location';

/**
 * Status of the current live location sharing session.
 *
 * - `not_sharing`       — no active session
 * - `permission_denied` — user denied foreground location permission
 * - `starting`          — waiting for session start to confirm
 * - `sharing`           — session is active and sending updates
 * - `stopping`          — waiting for stop/hide to confirm
 * - `error`             — last action failed; safe to retry
 */
export type LiveSharingStatus =
  | 'not_sharing'
  | 'permission_denied'
  | 'starting'
  | 'sharing'
  | 'stopping'
  | 'error';

/** Simplified coordinate exposed to UI/map — never log this value. */
export interface LiveLocationPosition {
  latitude: number;
  longitude: number;
}

export interface UseLiveLocationSessionResult {
  /** Current sharing status. */
  status: LiveSharingStatus;
  /** Duration the user has selected for the next session. */
  selectedDuration: LiveLocationDuration;
  /** ID of the active session returned by the backend, or null when not sharing. */
  sessionId: string | null;
  /** Human-readable error message from the last failed action, or null. */
  error: string | null;
  /** True while an async action is in flight. */
  isLoading: boolean;
  /** Latest successfully sent position, or null. Never log this value. */
  currentPosition: LiveLocationPosition | null;
  /** Time of the last successful position update sent to the backend, or null. */
  lastUpdatedAt: Date | null;
  /** Update the duration selection. Only applies when not actively sharing. */
  selectDuration: (duration: LiveLocationDuration) => void;
  /** Request foreground permission and start a new live location session. */
  startSession: () => Promise<void>;
  /** Stop the active session gracefully. */
  stopSession: () => Promise<void>;
  /**
   * Immediately remove position from all members and stop all active sessions.
   * "Hide me now" — must be fast and must not be blocked by loading state.
   */
  hideMeNow: () => Promise<void>;
}

const FALLBACK_ERROR_KEY = 'liveLocation.error';

// Throttle — send at most one update per POSITION_UPDATE_INTERVAL_MS
// and only after at least POSITION_UPDATE_DISTANCE_M meters of movement.
const POSITION_UPDATE_INTERVAL_MS = 5000; // 5 seconds
const POSITION_UPDATE_DISTANCE_M = 25; // 25 metres

// Do not expose raw backend error messages to users — they may be technical
// or in an unexpected language. Do not log errors — they could contain location
// data or auth tokens.

/**
 * Hook managing foreground live location session state.
 *
 * Flow:
 *  1. User selects duration.
 *  2. User calls startSession().
 *  3. App requests foreground location permission.
 *  4. If denied → status = 'permission_denied'.
 *  5. If granted → backend session started → GPS watcher started → status = 'sharing'.
 *  6. Watcher sends throttled position updates to backend (latest only, no history).
 *  7. User calls stopSession() or hideMeNow() → watcher removed → status = 'not_sharing'.
 *  8. On unmount → watcher removed.
 *
 * Privacy:
 *  - Coordinates are never logged.
 *  - No route history is stored.
 *  - Backend is the source of truth for access control.
 *  - Client-side checks are UI only; never trust them for security.
 */
export function useLiveLocationSession(): UseLiveLocationSessionResult {
  const [status, setStatus] = useState<LiveSharingStatus>('not_sharing');
  const [selectedDuration, setSelectedDuration] = useState<LiveLocationDuration>('1h');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [currentPosition, setCurrentPosition] = useState<LiveLocationPosition | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);

  // Refs avoid stale closure issues inside the location watcher callback.
  const sessionIdRef = useRef<string | null>(null);
  const locationSubRef = useRef<{ remove: () => void } | null>(null);

  // Remove GPS watcher and clear position state.
  const stopWatcher = useCallback(() => {
    locationSubRef.current?.remove();
    locationSubRef.current = null;
  }, []);

  // Clean up watcher on unmount or logout.
  useEffect(() => {
    return () => {
      locationSubRef.current?.remove();
      locationSubRef.current = null;
    };
  }, []);

  const selectDuration = useCallback(
    (duration: LiveLocationDuration) => {
      if (status === 'not_sharing' || status === 'error' || status === 'permission_denied') {
        setSelectedDuration(duration);
      }
    },
    [status],
  );

  const startSession = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    // Step 1: request foreground permission — only prompted on explicit user action.
    const { status: permStatus } = await ExpoLocation.requestForegroundPermissionsAsync();
    if (permStatus !== ExpoLocation.PermissionStatus.GRANTED) {
      setStatus('permission_denied');
      setIsLoading(false);
      return;
    }

    // Step 2: start backend session.
    setStatus('starting');
    let newSessionId: string;
    try {
      const response = await startLiveLocationSession({ duration: selectedDuration });
      newSessionId = response.data.session.id;
      setSessionId(newSessionId);
      sessionIdRef.current = newSessionId;
      setStatus('sharing');
    } catch {
      setError(FALLBACK_ERROR_KEY);
      setStatus('error');
      setIsLoading(false);
      return;
    }

    setIsLoading(false);

    // Step 3: start foreground GPS watcher with throttle.
    // Coordinates are never logged.
    try {
      const sub = await ExpoLocation.watchPositionAsync(
        {
          accuracy: ExpoLocation.Accuracy.Balanced,
          timeInterval: POSITION_UPDATE_INTERVAL_MS,
          distanceInterval: POSITION_UPDATE_DISTANCE_M,
        },
        (location) => {
          const sid = sessionIdRef.current;
          if (!sid) return;

          // Do not log coordinates.
          const coordinate: LiveLocationCoordinate = {
            latitude: location.coords.latitude,
            longitude: location.coords.longitude,
            accuracyMeters: location.coords.accuracy ?? undefined,
            headingDegrees: location.coords.heading ?? undefined,
            speedMetersPerSecond: location.coords.speed ?? undefined,
            recordedAt: new Date(location.timestamp).toISOString(),
          };

          setCurrentPosition({
            latitude: location.coords.latitude,
            longitude: location.coords.longitude,
          });
          setLastUpdatedAt(new Date());

          // Fire-and-forget — position update failures do not interrupt the session.
          updateLiveLocationPosition(sid, { coordinate }).catch(() => {
            // Non-sensitive diagnostic — do not log coordinates.
            console.warn('Live location: position update failed; session remains active.');
          });
        },
      );
      locationSubRef.current = sub;
    } catch {
      // Watcher failed to start; session is active on backend but updates won't flow.
      // Do not expose watcher error — user can still stop the session manually.
    }
  }, [selectedDuration]);

  const stopSession = useCallback(async () => {
    if (!sessionIdRef.current) return;
    stopWatcher();
    setCurrentPosition(null);
    setLastUpdatedAt(null);
    setStatus('stopping');
    setIsLoading(true);
    setError(null);
    const sid = sessionIdRef.current;
    try {
      await stopLiveLocationSession(sid, { reason: 'user_stop' });
      sessionIdRef.current = null;
      setSessionId(null);
      setStatus('not_sharing');
    } catch {
      setError(FALLBACK_ERROR_KEY);
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, [stopWatcher]);

  const hideMeNow = useCallback(async () => {
    stopWatcher();
    setCurrentPosition(null);
    setLastUpdatedAt(null);
    setStatus('stopping');
    setIsLoading(true);
    setError(null);
    try {
      await hideMeNowApi();
      sessionIdRef.current = null;
      setSessionId(null);
      setStatus('not_sharing');
    } catch {
      setError(FALLBACK_ERROR_KEY);
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, [stopWatcher]);

  return {
    status,
    selectedDuration,
    sessionId,
    error,
    isLoading,
    currentPosition,
    lastUpdatedAt,
    selectDuration,
    startSession,
    stopSession,
    hideMeNow,
  };
}
