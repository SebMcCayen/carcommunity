/**
 * Authentication context: AuthProvider + useAuth hook.
 *
 * Responsibilities:
 *   - Restore a previous session from secure storage on app startup.
 *   - Verify a restored session against GET /v1/auth/me.
 *   - Expose login / logout / refreshCurrentUser actions.
 *   - Store the backend session token securely (never in plain AsyncStorage).
 *
 * Security notes:
 *   - The session token is never exposed outside this module.
 *   - The session token is never logged at any log level.
 *   - Backend is the source of truth for identity, roles, access, and suspension.
 *   - Client-side auth state is for UX only — never use it as a security boundary.
 *
 * TODO (production hardening):
 *   - Implement silent token refresh before expiry.
 *   - Handle token expiration gracefully (clear state and redirect to login).
 *   - Send logout request to backend on explicit sign-out (revoke session).
 *   - Add biometric re-authentication for sensitive actions.
 *   - Backend must enforce role, subscription, and suspension checks independently.
 */

import { createContext, ReactNode, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Platform } from 'react-native';

import type { AuthenticatedUserSummary, AuthProvider as SharedAuthProvider, AuthResponse } from '@carcommunity/shared/auth';

import {
  getCurrentUser,
  loginWithApple,
  loginWithGoogle,
  loginWithApplePlaceholder,
  loginWithGooglePlaceholder,
  logoutPlaceholder,
} from '../api/auth';
import { publicEnv } from '../config/env';
import { clearSessionToken, loadSessionToken, saveSessionToken } from '../storage/tokenStorage';

export interface AuthContextValue {
  /** Authenticated user summary from the backend. Null when unauthenticated. */
  currentUser: AuthenticatedUserSummary | null;
  /** True when the session has been verified by the backend. */
  isAuthenticated: boolean;
  /** True while the initial session restore or any auth action is in progress. */
  isLoading: boolean;
  /** Human-readable error key from the last failed auth action. Null if no error. */
  error: string | null;
  /**
   * Attempt login for the given provider.
   * @devOnly identityToken is a placeholder — replace with real provider SDK output.
   */
  login: (provider: SharedAuthProvider, identityToken: string) => Promise<void>;
  /** Sign out: clear local state and request session revocation from the backend. */
  logout: () => Promise<void>;
  /** Re-fetch the current user from GET /v1/auth/me to sync with backend state. */
  refreshCurrentUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

type AuthProviderProps = {
  children: ReactNode;
};

export const AuthProvider = ({ children }: AuthProviderProps) => {
  const [currentUser, setCurrentUser] = useState<AuthenticatedUserSummary | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Keep the session token in a ref so it is never exposed via context.
  // Never log this value.
  const sessionTokenRef = useRef<string | null>(null);

  const clearAuthState = useCallback(() => {
    sessionTokenRef.current = null;
    setCurrentUser(null);
  }, []);

  /**
   * Restore a saved session from secure storage and verify it with the backend.
   * Runs once on mount. A failed restore does not block app startup.
   */
  useEffect(() => {
    let cancelled = false;

    const restoreSession = async () => {
      try {
        const stored = await loadSessionToken();

        if (!stored) {
          return;
        }

        const response = await getCurrentUser(stored.token);

        if (cancelled) return;

        if (response.ok) {
          sessionTokenRef.current = stored.token;
          setCurrentUser(response.data.user);
        } else {
          // Session is invalid or expired — clear stored credentials silently.
          await clearSessionToken();
        }
      } catch {
        // Session restore failure must not block the app.
        // Do not log errors that may contain token fragments.
        if (!cancelled) {
          await clearSessionToken().catch(() => undefined);
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    };

    void restoreSession();

    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (provider: SharedAuthProvider, identityToken: string) => {
    setIsLoading(true);
    setError(null);

    try {
      // In native auth mode use production login functions (no providerSubject,
      // includes appVersion/buildNumber). Fall back to placeholder functions in
      // dev mode so the app works without a real custom native build.
      type LoginFn = (token: string) => Promise<AuthResponse>;
      let loginFn: LoginFn;

      if (publicEnv.authMode === 'native') {
        loginFn = provider === 'apple' ? loginWithApple : loginWithGoogle;
      } else {
        loginFn = provider === 'apple' ? loginWithApplePlaceholder : loginWithGooglePlaceholder;
      }

      const response = await loginFn(identityToken);

      if (!response.ok) {
        setError('auth.errorGeneric');
        return;
      }

      const { user, session, token } = response.data;

      // Prefer the dedicated access token when present; fall back to session id.
      // TODO: Use a proper signed JWT once the backend issues one.
      const tokenToStore = token?._devOnly ? token.accessToken : session.sessionId;

      await saveSessionToken(tokenToStore, session.sessionId);
      sessionTokenRef.current = tokenToStore;
      setCurrentUser(user);
    } catch {
      setError('auth.errorGeneric');
    } finally {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      // Best-effort: tell the backend to revoke the session.
      // Clear local state regardless of the backend response.
      await logoutPlaceholder(sessionTokenRef.current ?? undefined).catch(() => undefined);
    } finally {
      await clearSessionToken().catch(() => undefined);
      clearAuthState();
      setIsLoading(false);
    }
  }, [clearAuthState]);

  const refreshCurrentUser = useCallback(async () => {
    const token = sessionTokenRef.current;

    if (!token) return;

    setIsLoading(true);
    setError(null);

    try {
      const response = await getCurrentUser(token);

      if (response.ok) {
        setCurrentUser(response.data.user);
      } else {
        // Session is no longer valid — sign out locally.
        await clearSessionToken().catch(() => undefined);
        clearAuthState();
      }
    } catch {
      setError('auth.errorGeneric');
    } finally {
      setIsLoading(false);
    }
  }, [clearAuthState]);

  const value: AuthContextValue = {
    currentUser,
    isAuthenticated: currentUser !== null,
    isLoading,
    error,
    login,
    logout,
    refreshCurrentUser,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = (): AuthContextValue => {
  const context = useContext(AuthContext);

  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }

  return context;
};

/**
 * Returns the platform-specific auth provider for the current device.
 *
 * TODO (production):
 *   - iOS: integrate expo-apple-authentication for real Apple Sign-In.
 *   - Android: integrate @react-native-google-signin/google-signin.
 *   - Remove this helper once real provider SDKs are wired in.
 */
export function getPlatformAuthProvider(): SharedAuthProvider | null {
  if (Platform.OS === 'ios') return 'apple';
  if (Platform.OS === 'android') return 'google';
  return null;
}
