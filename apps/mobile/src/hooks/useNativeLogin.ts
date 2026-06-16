/**
 * useNativeLogin — abstracts native provider sign-in for iOS (Apple) and Android (Google).
 *
 * iOS: Uses expo-apple-authentication (native Apple Sign-In).
 * Android: Uses @react-native-google-signin/google-signin (native Google Sign-In).
 *
 * Security notes:
 *   - The identity token is returned to the caller and must be forwarded
 *     directly to the backend login endpoint. It must NOT be stored,
 *     logged, or exposed beyond what is needed for the single login request.
 *   - Backend is the source of truth for identity, roles, and access.
 *   - Email is NOT used as primary identifier.
 *
 * TODO (nonce): Generate a cryptographically random nonce, pass it to the
 *   provider sign-in request, and forward it in the backend login request
 *   to prevent replay attacks.
 * TODO (production – Apple): Verify the "Sign in with Apple" capability is
 *   enabled in your Apple Developer account and App Store Connect before
 *   submitting to the App Store.
 * TODO (production – Google): Configure Google Play App Signing, add correct
 *   SHA-1/SHA-256 fingerprints to Google Cloud Console, and set
 *   EXPO_PUBLIC_GOOGLE_ANDROID_CLIENT_ID / EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID /
 *   EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID via EAS Secrets.
 * TODO (account linking): If the backend reports that a user already exists
 *   with a different identity provider, surface an account-linking flow
 *   rather than creating a duplicate account.
 */

import { Platform } from 'react-native';

import * as AppleAuthentication from 'expo-apple-authentication';
import { GoogleSignin, isErrorWithCode, statusCodes } from '@react-native-google-signin/google-signin';

import type { AuthProvider } from '@carcommunity/shared/auth';

import { publicEnv } from '../config/env';

export interface NativeLoginResult {
  /** Identity token from the native provider. Forward to the backend immediately; do not store. */
  identityToken: string;
  provider: AuthProvider;
}

/**
 * Error thrown when the user cancels the native sign-in flow.
 * Should be handled silently (no error shown in UI).
 */
export class NativeLoginCancelledError extends Error {
  constructor() {
    super('Sign-in cancelled by user');
    this.name = 'NativeLoginCancelledError';
  }
}

/**
 * Perform Apple Sign-In using expo-apple-authentication.
 *
 * Requests only the minimum required scope (FULL_NAME).
 * Email is NOT requested or used as the primary identifier.
 *
 * The returned identityToken is a JWT signed by Apple — pass it to the
 * backend for verification. Do not store or log it.
 *
 * TODO (nonce): Pass a cryptographic nonce to both requestedNonce and the
 *   backend login request once nonce handling is implemented.
 */
async function signInWithApple(): Promise<NativeLoginResult> {
  const credential = await AppleAuthentication.signInAsync({
    requestedScopes: [
      // Only FULL_NAME is requested. Email is intentionally excluded because:
      //   1. The backend identifies users by providerSubject from the verified
      //      identity token — email is not the primary identifier.
      //   2. Minimizing data collection reduces privacy risk and aligns with
      //      the product rule that email must not be the primary account ID.
      // If a display name is needed later, givenName/familyName from FULL_NAME
      // can be used. Apple only provides fullName on the first sign-in.
      AppleAuthentication.AppleAuthenticationScope.FULL_NAME,
    ],
    // TODO (nonce): requestedNonce: generatedNonce,
  });

  if (!credential.identityToken) {
    throw new Error('Apple Sign-In did not return an identity token.');
  }

  // identityToken is used only here — passed to the caller for backend verification.
  // It is NOT stored and must NOT be logged.
  return { identityToken: credential.identityToken, provider: 'apple' };
}

/**
 * Perform Google Sign-In using @react-native-google-signin/google-signin.
 *
 * Configures GoogleSignin with client IDs from Expo public environment variables.
 * The Google ID token (idToken) is returned for backend verification.
 *
 * TODO (production): Set EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID and
 *   EXPO_PUBLIC_GOOGLE_ANDROID_CLIENT_ID in your EAS Secrets / .env.
 * TODO (production): Configure SHA certificate fingerprints in Google Cloud Console.
 * TODO (production): The backend must validate the audience (aud) claim against
 *   the expected Google client ID.
 * TODO (nonce): Pass a nonce to both the sign-in request and the backend login
 *   request for replay protection once nonce handling is implemented.
 */
async function signInWithGoogle(): Promise<NativeLoginResult> {
  // Configure before each sign-in call. This is idempotent.
  // Client IDs are read from safe Expo public environment variables only.
  // Do NOT hardcode real client IDs here.
  GoogleSignin.configure({
    iosClientId: publicEnv.googleIosClientId || undefined,
    webClientId: publicEnv.googleWebClientId || undefined,
  });

  let result;
  try {
    result = await GoogleSignin.signIn();
  } catch (error) {
    if (isErrorWithCode(error)) {
      if (
        error.code === statusCodes.SIGN_IN_CANCELLED ||
        error.code === statusCodes.IN_PROGRESS
      ) {
        throw new NativeLoginCancelledError();
      }
    }
    throw error;
  }

  const googleResult =
    result && typeof result === 'object' && 'type' in result
      ? result.type === 'success'
        ? result.data
        : null
      : result;

  if (!googleResult?.idToken) {
    if (result && typeof result === 'object' && 'type' in result && result.type === 'cancelled') {
      throw new NativeLoginCancelledError();
    }
    throw new Error('Google Sign-In did not return an ID token.');
  }

  // idToken is used only here — passed to the caller for backend verification.
  // It is NOT stored and must NOT be logged.
  return { identityToken: googleResult.idToken, provider: 'google' };
}

/**
 * Hook that provides the `signIn` function for the current platform.
 *
 * Returns null from `signIn` when the platform is unsupported.
 * Throws `NativeLoginCancelledError` when the user cancels the flow.
 */
export function useNativeLogin() {
  const signIn = async (): Promise<NativeLoginResult | null> => {
    if (Platform.OS === 'ios') {
      return signInWithApple();
    }

    if (Platform.OS === 'android') {
      return signInWithGoogle();
    }

    // Unsupported platform — caller should handle null gracefully.
    return null;
  };

  return { signIn };
}
