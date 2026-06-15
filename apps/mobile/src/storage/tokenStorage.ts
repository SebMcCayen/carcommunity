/**
 * Secure session token storage backed by expo-secure-store (iOS Keychain /
 * Android Keystore). Tokens are never written to plain AsyncStorage.
 *
 * TODO (production hardening):
 *   - Verify token expiration before returning a stored token (check expiresAt).
 *   - Implement session rotation / silent refresh before expiry.
 *   - Revoke session on the backend when the user logs out.
 *   - Consider requiring biometric authentication for sensitive operations.
 *   - Do not log tokens at any log level — not even in dev builds.
 *   - Do not store raw Apple or Google identity tokens; only the backend
 *     session token should be persisted here.
 */

import * as SecureStore from 'expo-secure-store';

const SESSION_TOKEN_KEY = 'kcc_session_token';
const SESSION_ID_KEY = 'kcc_session_id';

export interface StoredSession {
  token: string;
  sessionId: string;
}

/** Persist the backend session token securely. Never log the token value. */
export async function saveSessionToken(token: string, sessionId: string): Promise<void> {
  await SecureStore.setItemAsync(SESSION_TOKEN_KEY, token);
  await SecureStore.setItemAsync(SESSION_ID_KEY, sessionId);
}

/**
 * Load the stored session token. Returns null if no token is stored or
 * if secure storage is unavailable.
 */
export async function loadSessionToken(): Promise<StoredSession | null> {
  const token = await SecureStore.getItemAsync(SESSION_TOKEN_KEY);
  const sessionId = await SecureStore.getItemAsync(SESSION_ID_KEY);

  if (!token || !sessionId) return null;

  return { token, sessionId };
}

/** Remove the stored session token — call this on logout. */
export async function clearSessionToken(): Promise<void> {
  await SecureStore.deleteItemAsync(SESSION_TOKEN_KEY);
  await SecureStore.deleteItemAsync(SESSION_ID_KEY);
}
