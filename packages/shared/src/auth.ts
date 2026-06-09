/**
 * Shared authentication contract for API and clients.
 * Backend remains the source of truth for all auth and access decisions.
 */

export const AUTH_PROVIDERS = ['apple', 'google'] as const;

export type AuthProvider = (typeof AUTH_PROVIDERS)[number];

export const AUTH_ROUTE_PATHS = {
  login: '/v1/auth/login',
  logout: '/v1/auth/logout',
  me: '/v1/auth/me',
} as const;

export type AuthErrorCode =
  | 'validation_error'
  | 'internal_error'
  | 'not_found'
  | 'provider_verification_not_implemented'
  | 'unauthenticated'
  | 'not_implemented';

/** Roles defined and enforced by the backend. Never trust client-side role claims. */
export type Role = 'user' | 'admin' | 'owner';

/**
 * Login request sent from the mobile client to POST /v1/auth/login.
 * Email is intentionally absent — provider subject is the stable identifier.
 */
export interface LoginRequest {
  provider: AuthProvider;
  identityToken: string;
  /** Stable subject identifier extracted client-side from the identity token. */
  providerSubject?: string;
  /** Client platform (ios | android). */
  platform?: string;
  /** App version string (e.g. "1.0.0"). */
  appVersion?: string;
  /** App build number. */
  buildNumber?: string;
}

/** @deprecated Use LoginRequest instead. */
export type AuthRequest = LoginRequest;

export interface AuthIdentity {
  provider: AuthProvider;
  providerSubject: string;
}

/** Summary of the authenticated user returned by the backend. */
export interface AuthenticatedUserSummary {
  userId: string;
  identities: AuthIdentity[];
  roles: Role[];
  displayName?: string | null;
  avatarUrl?: string | null;
}

/** @deprecated Use AuthenticatedUserSummary instead. */
export type AuthUser = AuthenticatedUserSummary;

export interface AuthSession {
  sessionId: string;
  expiresAt: string;
}

/**
 * Placeholder token response shape.
 * @remarks NOT PRODUCTION-READY — this is a development-only placeholder.
 *   Real tokens must be signed JWTs with proper issuer, audience, and expiry.
 *   Replace before any production deployment.
 */
export interface TokenResponsePlaceholder {
  /** Development-only marker. Must be removed before production use. */
  _devOnly: true;
  accessToken: string;
  expiresIn: number;
}

export type AuthResponse =
  | {
      ok: true;
      data: {
        user: AuthenticatedUserSummary;
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
