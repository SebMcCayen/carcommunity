import { useCallback, useState } from 'react';

import type { LiveLocationDuration } from '@carcommunity/shared/live-location';

import {
  hideMeNow as hideMeNowApi,
  startLiveLocationSession,
  stopLiveLocationSession,
} from '../api/live-location';

/**
 * Status of the current live location sharing session.
 *
 * - `not_sharing`  — no active session
 * - `starting`     — waiting for session start to confirm
 * - `sharing`      — session is active
 * - `stopping`     — waiting for stop/hide to confirm
 * - `error`        — last action failed; safe to retry
 */
export type LiveSharingStatus = 'not_sharing' | 'starting' | 'sharing' | 'stopping' | 'error';

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
  /** Update the duration selection. Only applies when not actively sharing. */
  selectDuration: (duration: LiveLocationDuration) => void;
  /** Start a new live location session with the selected duration. */
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

function extractErrorMessage(_err: unknown): string {
  // Do not expose raw backend error messages to users — they may be technical
  // or in an unexpected language. Return an i18n key so the UI can translate it.
  // Do not log the error — it could contain location data or auth tokens.
  return FALLBACK_ERROR_KEY;
}

/**
 * Lightweight hook managing live location session state.
 *
 * State is local to this hook — the backend is the source of truth.
 * Persisting session across app restarts or background state requires
 * additional work (session recovery from backend).
 *
 * TODO: Add session recovery — check the backend for an existing active session on mount.
 * TODO: Request foreground location permission before starting a session.
 * TODO: Request background location only during an active session.
 * TODO: Add Android foreground notification when background updates are introduced.
 * TODO: Add iOS background location handling only during an active session.
 * TODO: Throttle position updates to ~25–50 m or 5–10 s once device GPS is wired in.
 * TODO: Send only the latest position — no route history.
 * TODO: Enforce safe driving mode — suppress distracting interactions when device is in motion.
 * TODO: Backend must enforce feature access. Never unlock features purely client-side.
 */
export function useLiveLocationSession(): UseLiveLocationSessionResult {
  const [status, setStatus] = useState<LiveSharingStatus>('not_sharing');
  const [selectedDuration, setSelectedDuration] = useState<LiveLocationDuration>('1h');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const selectDuration = useCallback(
    (duration: LiveLocationDuration) => {
      if (status === 'not_sharing' || status === 'error') {
        setSelectedDuration(duration);
      }
    },
    [status],
  );

  const startSession = useCallback(async () => {
    setStatus('starting');
    setIsLoading(true);
    setError(null);
    try {
      const response = await startLiveLocationSession({ duration: selectedDuration });
      setSessionId(response.data.session.id);
      setStatus('sharing');
    } catch (err) {
      setError(extractErrorMessage(err));
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, [selectedDuration]);

  const stopSession = useCallback(async () => {
    if (!sessionId) return;
    setStatus('stopping');
    setIsLoading(true);
    setError(null);
    try {
      await stopLiveLocationSession(sessionId, { reason: 'user_stop' });
      setSessionId(null);
      setStatus('not_sharing');
    } catch (err) {
      setError(extractErrorMessage(err));
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, [sessionId]);

  const hideMeNow = useCallback(async () => {
    setStatus('stopping');
    setIsLoading(true);
    setError(null);
    try {
      await hideMeNowApi();
      setSessionId(null);
      setStatus('not_sharing');
    } catch (err) {
      setError(extractErrorMessage(err));
      setStatus('error');
    } finally {
      setIsLoading(false);
    }
  }, []);

  return {
    status,
    selectedDuration,
    sessionId,
    error,
    isLoading,
    selectDuration,
    startSession,
    stopSession,
    hideMeNow,
  };
}
