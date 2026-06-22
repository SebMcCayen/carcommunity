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

import { publicEnv } from '../config/env';

import {
  clearLiveLocationSession,
  isSessionExpired,
  loadLiveLocationSession,
  saveLiveLocationSession,
} from '../storage/liveLocationStorage';
import { loadSessionToken } from '../storage/tokenStorage';

import {
  BACKGROUND_LOCATION_TASK_NAME,
  startBackgroundLocationUpdates,
  stopBackgroundLocationUpdates,
} from '../session/backgroundLocationTask';

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

/**
 * Background permission mode for the active session.
 *
 * - `not_requested`  — background permission has not been requested yet
 * - `granted`        — background permission granted; task is or will be running
 * - `foreground_only`— user declined background permission; sharing is foreground-only
 */
export type BackgroundPermissionMode = 'not_requested' | 'granted' | 'foreground_only';

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
  /** When the active session expires, or null when not sharing. */
  sessionExpiresAt: Date | null;
  /**
   * Background permission mode for the current session.
   * Only meaningful while status === 'sharing'.
   */
  backgroundPermissionMode: BackgroundPermissionMode;
  /** Human-readable error message from the last failed action, or null. */
  error: string | null;
  /** True while an async action is in flight. */
  isLoading: boolean;
  /** Latest successfully sent position, or null. Never log this value. */
  currentPosition: LiveLocationPosition | null;
  /** Time of the last successful position update sent to the backend, or null. */
  lastUpdatedAt: Date | null;
  /**
   * The ID of the session that was most recently stopped.
   * Set after a successful stopSession call; cleared once the save prompt is dismissed.
   * Used by the save prompt to offer save/discard after stop.
   * Never set after hideMeNow — hide prioritises privacy and skips the save prompt.
   */
  stoppedSessionId: string | null;
  /** Dismiss the save prompt (e.g. after user saves, discards, or closes the modal). */
  dismissSavePrompt: () => void;
  /** Update the duration selection. Only applies when not actively sharing. */
  selectDuration: (duration: LiveLocationDuration) => void;
  /** Request foreground permission and start a new live location session. */
  startSession: () => Promise<void>;
  /** Stop the active session gracefully. */
  stopSession: () => Promise<void>;
  /**
   * Immediately remove position from all members and stop all active sessions.
   * "Hide me now" — must be fast and must not be blocked by loading state.
   * Privacy action: never shows a save prompt.
   */
  hideMeNow: () => Promise<void>;
  /**
   * Request background location permission after the user explicitly agrees.
   *
   * Must only be called after the user has read the background permission
   * rationale and tapped the confirmation button.
   * Must never be called at startup or during onboarding.
   *
   * If granted, background location updates start for the active session.
   * If denied, the session continues in foreground-only mode.
   *
   * @param notificationTitle - Android foreground service notification title.
   *   Callers with i18n access (e.g. the screen) should supply a translated string.
   * @param notificationBody  - Android foreground service notification body.
   *   Callers with i18n access (e.g. the screen) should supply a translated string.
   */
  requestBackgroundPermission: (notificationTitle?: string, notificationBody?: string) => Promise<void>;
  /**
   * Dismiss the background permission rationale without requesting permission.
   *
   * The user has seen the rationale and chosen to continue with foreground-only
   * sharing. Do not show the rationale again.
   */
  skipBackgroundPermission: () => void;
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
 * Hook managing live location session state with foreground and optional background updates.
 *
 * Foreground flow:
 *  1. User selects duration.
 *  2. User calls startSession().
 *  3. App requests foreground location permission.
 *  4. If denied → status = 'permission_denied'.
 *  5. If granted → backend session started → GPS watcher started → status = 'sharing'.
 *  6. Watcher sends throttled position updates to backend (latest only, no history).
 *  7. User calls stopSession() or hideMeNow() → watcher removed → status = 'not_sharing'.
 *  8. On unmount → watcher and background task removed.
 *
 * Background flow (after explicit user opt-in):
 *  1. User reads background permission rationale and calls requestBackgroundPermission().
 *  2. App requests background location permission.
 *  3. If denied → backgroundPermissionMode = 'foreground_only'; sharing continues foreground-only.
 *  4. If granted → backgroundPermissionMode = 'granted'; background task started.
 *  5. Background task fires when app is backgrounded, updating backend position.
 *  6. Background task stops when session ends, is hidden, expires, or user logs out.
 *
 * Session restoration:
 *  - On mount, the hook checks secure storage for a previously saved session reference.
 *  - If a non-expired session reference is found, state is restored optimistically.
 *  - Backend is the source of truth: the next position update attempt will verify validity.
 *  - If the stored session has expired, it is cleared and the background task is stopped.
 *
 * Privacy:
 *  - Coordinates are never logged.
 *  - No route history is stored.
 *  - Backend is the source of truth for access control.
 *  - Client-side checks are UI only; never trust them for security.
 *  - Background sharing is always visible and controllable by the user.
 */
export function useLiveLocationSession(): UseLiveLocationSessionResult {
  const [status, setStatus] = useState<LiveSharingStatus>('not_sharing');
  const [selectedDuration, setSelectedDuration] = useState<LiveLocationDuration>('1h');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sessionExpiresAt, setSessionExpiresAt] = useState<Date | null>(null);
  const [backgroundPermissionMode, setBackgroundPermissionMode] =
    useState<BackgroundPermissionMode>('not_requested');
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [currentPosition, setCurrentPosition] = useState<LiveLocationPosition | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  /**
   * The ID of the most recently stopped session.
   * Set after stopSession succeeds so the LiveLocationScreen can show the
   * save/discard prompt. Never set by hideMeNow — that privacy action skips
   * the prompt entirely.
   */
  const [stoppedSessionId, setStoppedSessionId] = useState<string | null>(null);

  // Refs avoid stale closure issues inside the location watcher callback.
  const sessionIdRef = useRef<string | null>(null);
  const locationSubRef = useRef<{ remove: () => void } | null>(null);
  const sessionTokenRef = useRef<string | null>(null);

  const persistSessionBackgroundMode = useCallback(
    async (
      mode: BackgroundPermissionMode,
      options?: { notificationTitle?: string; notificationBody?: string },
    ) => {
      const stored = await loadLiveLocationSession().catch(() => null);
      if (!stored) return;
      await saveLiveLocationSession({
        ...stored,
        backgroundPermissionMode: mode,
        notificationTitle: options?.notificationTitle ?? stored.notificationTitle,
        notificationBody: options?.notificationBody ?? stored.notificationBody,
      }).catch(() => undefined);
    },
    [],
  );

  // Remove the GPS watcher subscription.
  const stopWatcher = useCallback(() => {
    locationSubRef.current?.remove();
    locationSubRef.current = null;
  }, []);

  // Start the foreground GPS watcher for the given session ID.
  // Coordinates are never logged.
  const startForegroundWatcher = useCallback((sid: string) => {
    ExpoLocation.watchPositionAsync(
      {
        accuracy: ExpoLocation.Accuracy.Balanced,
        timeInterval: POSITION_UPDATE_INTERVAL_MS,
        distanceInterval: POSITION_UPDATE_DISTANCE_M,
      },
      (location) => {
        const currentSid = sessionIdRef.current;
        if (!currentSid) return;

        // Do not log coordinates.
        const coordinate: LiveLocationCoordinate = {
          latitude: location.coords.latitude,
          longitude: location.coords.longitude,
          accuracyMeters: location.coords.accuracy ?? undefined,
          headingDegrees: location.coords.heading ?? undefined,
          speedMetersPerSecond: location.coords.speed ?? undefined,
          recordedAt: new Date(location.timestamp).toISOString(),
        };

        // Update position state only after the backend confirms receipt.
        // Fire-and-forget — position update failures do not interrupt the session.
        updateLiveLocationPosition(currentSid, { coordinate }, sessionTokenRef.current ?? undefined)
          .then(() => {
            setCurrentPosition({
              latitude: location.coords.latitude,
              longitude: location.coords.longitude,
            });
            setLastUpdatedAt(new Date());
          })
          .catch(() => {
            // Non-sensitive diagnostic — do not log coordinates.
            console.warn('Live location: position update failed; session remains active.');
          });
      },
    )
      .then((sub) => {
        locationSubRef.current = sub;
      })
      .catch(() => {
        // Watcher failed to start; session is active on backend but foreground updates
        // won't flow. User can still stop the session manually.
      });
  }, []);

  // Session restoration: on mount, check whether a session was active before the app closed.
  // Backend is the source of truth; we restore state optimistically and verify on next update.
  useEffect(() => {
    let mounted = true;

    async function restoreSession() {
      const stored = await loadLiveLocationSession().catch(() => null);
      if (!mounted) return;
      if (!stored) return;

      if (isSessionExpired(stored)) {
        // Stored session has expired — clear storage and stop any lingering background task.
        await clearLiveLocationSession().catch(() => undefined);
        await stopBackgroundLocationUpdates().catch(() => undefined);
        return;
      }

      // Restore session state. The next position update attempt will verify with the backend.
      sessionIdRef.current = stored.sessionId;
      setSessionId(stored.sessionId);
      setSessionExpiresAt(new Date(stored.expiresAt));
      setStatus('sharing');
      setBackgroundPermissionMode(stored.backgroundPermissionMode ?? 'not_requested');
      const auth = await loadSessionToken().catch(() => null);
      if (!mounted) return;
      sessionTokenRef.current = auth?.token ?? null;

      // Check whether background permission is currently granted and task is running.
      const bgPermission = await ExpoLocation.getBackgroundPermissionsAsync().catch(() => null);
      if (!mounted) return;

      if (bgPermission?.granted === true) {
        setBackgroundPermissionMode('granted');
        void persistSessionBackgroundMode('granted');
        // Ensure the background task is running for this session.
        const taskRunning = await ExpoLocation.hasStartedLocationUpdatesAsync(
          BACKGROUND_LOCATION_TASK_NAME,
        ).catch(() => false);
        if (!mounted) return;
        if (!taskRunning) {
          await startBackgroundLocationUpdates(
            stored.notificationTitle,
            stored.notificationBody,
          ).catch(() => undefined);
        }
      }

      // Restart the foreground watcher for UI position feedback.
      startForegroundWatcher(stored.sessionId);
    }

    restoreSession();

    return () => {
      mounted = false;
    };
  }, [persistSessionBackgroundMode, startForegroundWatcher]);

  // Clean up watcher and background task on unmount (e.g. logout).
  useEffect(() => {
    return () => {
      locationSubRef.current?.remove();
      locationSubRef.current = null;
      sessionTokenRef.current = null;
      // Stop background task on unmount — covers the logout case.
      stopBackgroundLocationUpdates().catch(() => undefined);
      clearLiveLocationSession().catch(() => undefined);
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
    let expiresAt: string;
    try {
      const auth = await loadSessionToken().catch(() => null);
      sessionTokenRef.current = auth?.token ?? null;
      const response = await startLiveLocationSession(
        { duration: selectedDuration },
        sessionTokenRef.current ?? undefined,
      );
      newSessionId = response.data.session.id;
      expiresAt = response.data.session.expiresAt;
      setSessionId(newSessionId);
      setSessionExpiresAt(new Date(expiresAt));
      sessionIdRef.current = newSessionId;
      setStatus('sharing');
    } catch {
      setError(FALLBACK_ERROR_KEY);
      setStatus('error');
      setIsLoading(false);
      return;
    }

    setIsLoading(false);

    // Step 3: persist session reference for background task use.
    await saveLiveLocationSession({
      sessionId: newSessionId,
      expiresAt,
      apiBaseUrl: publicEnv.apiBaseUrl,
      backgroundPermissionMode: 'not_requested',
    }).catch(() => undefined);

    // Step 4: start foreground GPS watcher.
    startForegroundWatcher(newSessionId);

    // Background task is NOT started here. The user must explicitly call
    // requestBackgroundPermission() after reading the rationale.
  }, [selectedDuration, startForegroundWatcher]);

  /**
   * Request background location permission after the user explicitly agrees.
   *
   * Calls requestBackgroundPermissionsAsync() only on explicit user action.
   * Never called at startup or during onboarding.
   *
   * If granted: starts background location updates for the active session.
   * If denied:  continues with foreground-only sharing without pressure to retry.
   */
  const requestBackgroundPermission = useCallback(
    async (notificationTitle?: string, notificationBody?: string) => {
      const { status: permStatus } = await ExpoLocation.requestBackgroundPermissionsAsync();

      if (permStatus === ExpoLocation.PermissionStatus.GRANTED) {
        // Start background task only if a session is currently active.
        // Caller supplies i18n-translated strings for the Android notification.
        if (sessionIdRef.current) {
          const started = await startBackgroundLocationUpdates(
            notificationTitle,
            notificationBody,
          ).catch(() => false);
          if (started) {
            setBackgroundPermissionMode('granted');
            await persistSessionBackgroundMode('granted', {
              notificationTitle,
              notificationBody,
            });
          } else {
            setBackgroundPermissionMode('foreground_only');
            await persistSessionBackgroundMode('foreground_only');
          }
        } else {
          setBackgroundPermissionMode('granted');
        }
      } else {
        // User declined — stay in foreground-only mode; do not pressure the user again.
        setBackgroundPermissionMode('foreground_only');
        await persistSessionBackgroundMode('foreground_only');
      }
    },
    [persistSessionBackgroundMode],
  );

  /** Dismiss the background permission rationale; continue with foreground-only sharing. */
  const skipBackgroundPermission = useCallback(() => {
    setBackgroundPermissionMode('foreground_only');
    void persistSessionBackgroundMode('foreground_only');
  }, [persistSessionBackgroundMode]);

  /** Stop the active session and all location updates. */
  const stopSession = useCallback(async () => {
    if (!sessionIdRef.current) return;
    // Stop local updates immediately — do not wait for the next scheduled update.
    stopWatcher();
    await stopBackgroundLocationUpdates().catch(() => undefined);
    await clearLiveLocationSession().catch(() => undefined);
    setCurrentPosition(null);
    setLastUpdatedAt(null);
    setBackgroundPermissionMode('not_requested');
    setSessionExpiresAt(null);
    setStatus('stopping');
    setIsLoading(true);
    setError(null);
    const sid = sessionIdRef.current;
    try {
      await stopLiveLocationSession(sid, { reason: 'user_stop' }, sessionTokenRef.current ?? undefined);
      sessionIdRef.current = null;
      sessionTokenRef.current = null;
      setSessionId(null);
      setStatus('not_sharing');
      // Expose the stopped session ID so the screen can offer the save/discard prompt.
      // Never set this from hideMeNow — that privacy action always discards silently.
      setStoppedSessionId(sid);
    } catch {
      setError(FALLBACK_ERROR_KEY);
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, [stopWatcher]);

  /** Immediately remove position and stop all updates. Privacy action — never blocked. */
  const hideMeNow = useCallback(async () => {
    // Stop local updates immediately — do not wait for the next scheduled update.
    stopWatcher();
    await stopBackgroundLocationUpdates().catch(() => undefined);
    await clearLiveLocationSession().catch(() => undefined);
    setCurrentPosition(null);
    setLastUpdatedAt(null);
    setBackgroundPermissionMode('not_requested');
    setSessionExpiresAt(null);
    setStatus('stopping');
    setIsLoading(true);
    setError(null);
    try {
      await hideMeNowApi(sessionTokenRef.current ?? undefined);
      sessionIdRef.current = null;
      sessionTokenRef.current = null;
      setSessionId(null);
      setStatus('not_sharing');
    } catch {
      setError(FALLBACK_ERROR_KEY);
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, [stopWatcher]);

  /** Dismiss the save prompt once the user has saved, discarded, or closed the modal. */
  const dismissSavePrompt = useCallback(() => {
    setStoppedSessionId(null);
  }, []);

  return {
    status,
    selectedDuration,
    sessionId,
    sessionExpiresAt,
    backgroundPermissionMode,
    error,
    isLoading,
    currentPosition,
    lastUpdatedAt,
    stoppedSessionId,
    dismissSavePrompt,
    selectDuration,
    startSession,
    stopSession,
    hideMeNow,
    requestBackgroundPermission,
    skipBackgroundPermission,
  };
}
