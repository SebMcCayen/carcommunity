/**
 * Admin portal authentication via Firebase Authentication with Google Sign-In.
 *
 * Security model:
 * - Authentication is performed by Firebase Authentication (Google provider).
 * - Admin authorization is enforced through the `admin: true` Firebase custom claim.
 * - Custom claims are set exclusively by trusted backend code (Firebase Admin SDK).
 *   Clients cannot assign or modify custom claims.
 * - Hiding UI elements is NOT authorization — the backend independently
 *   verifies the `admin` claim on every protected API request.
 * - The Firebase ID token is sent as a ****** on all API requests so the
 *   backend can verify the claim server-side.
 *
 * Do NOT cache admin status beyond the lifetime of an active Firebase auth session.
 */

import {
  GoogleAuthProvider,
  signInWithPopup,
  signOut as firebaseSignOut,
  onAuthStateChanged,
  type User,
} from 'firebase/auth';
import { getFirebaseAuth } from './firebase';

const googleProvider = new GoogleAuthProvider();

export interface AdminAuthState {
  user: User | null;
  isAdmin: boolean;
  loading: boolean;
}

/**
 * Signs in with Google via a popup window.
 * After sign-in the admin claim is checked; if the user does not hold
 * `admin: true` they are signed out immediately.
 *
 * @throws When the Google sign-in popup is dismissed or fails.
 * @returns The signed-in Firebase User if successful.
 */
export async function signInWithGoogle(): Promise<User> {
  const auth = getFirebaseAuth();
  const result = await signInWithPopup(auth, googleProvider);
  const isAdmin = await checkAdminClaim(result.user);

  if (!isAdmin) {
    await firebaseSignOut(auth);
    throw new Error('not_admin');
  }

  return result.user;
}

/**
 * Signs out the current user.
 */
export async function signOut(): Promise<void> {
  return firebaseSignOut(getFirebaseAuth());
}

/**
 * Checks whether the currently signed-in user holds the `admin: true` Firebase
 * custom claim.
 *
 * The token is force-refreshed so that a recently assigned claim is detected
 * without requiring the user to sign out and back in.
 *
 * Note: this is a UI hint only. The backend independently verifies the claim
 * on every protected API request.
 */
export async function checkAdminClaim(user: User): Promise<boolean> {
  const tokenResult = await user.getIdTokenResult(/* forceRefresh */ true);
  return tokenResult.claims['admin'] === true;
}

/**
 * Returns the current Firebase ID token for use in API request Authorization
 * headers. Returns null if the user is not authenticated.
 *
 * Firebase ID tokens expire after 1 hour. Calling this function always returns
 * a fresh token (auto-refreshed by the Firebase SDK when close to expiry).
 */
export async function getCurrentIdToken(user: User | null): Promise<string | null> {
  if (!user) return null;
  return user.getIdToken(/* forceRefresh */ false);
}

/**
 * Subscribes to Firebase auth state changes.
 * Returns an unsubscribe function.
 */
export function onAdminAuthStateChanged(
  callback: (user: User | null) => void,
): () => void {
  return onAuthStateChanged(getFirebaseAuth(), callback);
}
