/**
 * Secure storage for the active live location session reference.
 *
 * Used by the background location task to know which session is active, when
 * it expires, and which API endpoint to call — all without access to React state.
 *
 * Privacy:
 *  - Stores only the session ID, expiry timestamp, and API base URL.
 *  - Does not store coordinates, route history, or authentication tokens.
 *  - Cleared immediately when sharing stops, is hidden, or expires.
 */

import * as SecureStore from 'expo-secure-store';

const LIVE_LOCATION_SESSION_KEY = 'carcommunity_live_location_bg_session';

export interface StoredLiveLocationSession {
  /** Active session ID returned by the backend. */
  sessionId: string;
  /** ISO 8601 expiry timestamp. Background task uses this to stop early. */
  expiresAt: string;
  /** API base URL for the background task's position update requests. */
  apiBaseUrl: string;
  /** Last known background permission mode for this active session. */
  backgroundPermissionMode?: 'not_requested' | 'granted' | 'foreground_only';
  /** Localized Android foreground service notification title for this session. */
  notificationTitle?: string;
  /** Localized Android foreground service notification body for this session. */
  notificationBody?: string;
}

/** Persist the active live location session reference for background task use. */
export async function saveLiveLocationSession(
  session: StoredLiveLocationSession,
): Promise<void> {
  await SecureStore.setItemAsync(LIVE_LOCATION_SESSION_KEY, JSON.stringify(session));
}

/**
 * Load the stored session reference. Returns null if nothing is stored or if
 * the stored value cannot be parsed.
 */
export async function loadLiveLocationSession(): Promise<StoredLiveLocationSession | null> {
  const raw = await SecureStore.getItemAsync(LIVE_LOCATION_SESSION_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!isValidStoredSession(parsed)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

/** Remove the session reference — call this when sharing ends for any reason. */
export async function clearLiveLocationSession(): Promise<void> {
  await SecureStore.deleteItemAsync(LIVE_LOCATION_SESSION_KEY);
}

/** Returns true if the stored session's expiry time has already passed. */
export function isSessionExpired(session: StoredLiveLocationSession): boolean {
  const expiresAtMs = Date.parse(session.expiresAt);
  if (Number.isNaN(expiresAtMs)) {
    return true;
  }
  return expiresAtMs <= Date.now();
}

function isValidStoredSession(value: unknown): value is StoredLiveLocationSession {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<StoredLiveLocationSession>;
  if (typeof candidate.sessionId !== 'string' || candidate.sessionId.trim().length === 0) return false;
  if (typeof candidate.apiBaseUrl !== 'string' || candidate.apiBaseUrl.trim().length === 0) return false;
  if (
    typeof candidate.expiresAt !== 'string' ||
    Number.isNaN(Date.parse(candidate.expiresAt))
  ) {
    return false;
  }
  if (
    candidate.backgroundPermissionMode !== undefined &&
    candidate.backgroundPermissionMode !== 'not_requested' &&
    candidate.backgroundPermissionMode !== 'granted' &&
    candidate.backgroundPermissionMode !== 'foreground_only'
  ) {
    return false;
  }
  if (candidate.notificationTitle !== undefined && typeof candidate.notificationTitle !== 'string') {
    return false;
  }
  if (candidate.notificationBody !== undefined && typeof candidate.notificationBody !== 'string') {
    return false;
  }
  return true;
}
