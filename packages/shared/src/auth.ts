/**
 * Shared authentication contract for API and clients.
 * Backend remains the source of truth for all auth and access decisions.
 */

export const AUTH_PROVIDERS = ['apple', 'google'] as const;

export type AuthProvider = (typeof AUTH_PROVIDERS)[number];

export const AUTH_ROUTE_PATHS = {
  mobileLogin: '/v1/auth/mobile-login',
  logout: '/v1/auth/logout',
  me: '/v1/me',
} as const;

export type AuthErrorCode =
  | 'validation_error'
  | 'internal_error'
  | 'not_found'
  | 'provider_verification_not_implemented'
  | 'unauthenticated'
  | 'not_implemented';

export interface AuthRequest {
  provider: AuthProvider;
  identityToken: string;
}

export interface AuthIdentity {
  provider: AuthProvider;
  providerSubject: string;
}

export interface AuthUser {
  userId: string;
  identities: AuthIdentity[];
  displayName?: string | null;
  avatarUrl?: string | null;
}

export interface AuthSession {
  sessionId: string;
  expiresAt: string;
}

export type AuthResponse =
  | {
      ok: true;
      data: {
        user: AuthUser;
        session: AuthSession;
      };
    }
  | {
      ok: false;
      error: {
        code: AuthErrorCode;
        message: string;
      details?: Record<string, unknown>;
    };
  };
