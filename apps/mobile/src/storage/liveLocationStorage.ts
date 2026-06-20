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

const LIVE_LOCATION_SESSION_KEY = 'kcc_live_location_bg_session';

export interface StoredLiveLocationSession {
  /** Active session ID returned by the backend. */
  sessionId: string;
  /** ISO 8601 expiry timestamp. Background task uses this to stop early. */
  expiresAt: string;
  /** API base URL for the background task's position update requests. */
  apiBaseUrl: string;
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
    return JSON.parse(raw) as StoredLiveLocationSession;
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
  return new Date(session.expiresAt) <= new Date();
}
