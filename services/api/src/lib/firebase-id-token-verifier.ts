/**
 * Firebase ID token verifier abstraction.
 *
 * Wraps the Firebase Admin Auth SDK to verify Firebase ID tokens and extract
 * the trusted identity from them. The interface is kept narrow so that tests
 * can inject a lightweight fake without depending on the real Firebase SDK.
 *
 * Security guarantees (production):
 * - Tokens are verified against Firebase's public keys.
 * - Expiry, issuer, and audience are all validated by Firebase Admin SDK.
 * - Custom claims (e.g. `admin: true`) can only be set by trusted backend
 *   code; clients cannot forge them because they cannot sign a valid token.
 */

import type { Auth } from 'firebase-admin/auth';

import { AppError } from './errors.js';

/** Decoded, trusted payload extracted from a verified Firebase ID token. */
export interface DecodedFirebaseToken {
  /** Firebase UID — the canonical user identity. */
  uid: string;
  /** Email address from the token, if present. */
  email: string | null;
  /**
   * Whether the user has the `admin: true` Firebase custom claim.
   * Only set by trusted backend code via Firebase Admin SDK.
   * Clients cannot assign this claim.
   */
  isAdmin: boolean;
}

export interface FirebaseIdTokenVerifier {
  /**
   * Verifies a Firebase ID token and returns the decoded payload.
   * Throws AppError(401, 'invalid_identity_token') if the token is missing,
   * malformed, expired, or otherwise invalid.
   */
  verifyIdToken(token: string): Promise<DecodedFirebaseToken>;
}

/**
 * Creates a FirebaseIdTokenVerifier backed by the Firebase Admin Auth SDK.
 * The Auth instance is injected to make the verifier testable.
 */
export function createFirebaseIdTokenVerifier(auth: Auth): FirebaseIdTokenVerifier {
  return {
    async verifyIdToken(token) {
      if (!token) {
        throw new AppError(401, 'invalid_identity_token', 'Firebase ID token is missing.');
      }

      try {
        const decoded = await auth.verifyIdToken(token, /* checkRevoked */ false);

        return {
          uid: decoded.uid,
          email: decoded.email ?? null,
          isAdmin: decoded['admin'] === true,
        };
      } catch (error) {
        if (error instanceof AppError) {
          throw error;
        }

        // Firebase Admin SDK throws errors with a code property.
        // All Firebase token errors map to 401 — never expose the raw error.
        throw new AppError(401, 'invalid_identity_token', 'Firebase ID token is invalid or expired.');
      }
    },
  };
}
