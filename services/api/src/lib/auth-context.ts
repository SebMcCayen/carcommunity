/**
 * Placeholder auth context for backend request authorization.
 *
 * @remarks
 *   When FIREBASE_PROJECT_ID is configured, the primary auth path verifies
 *   Firebase ID tokens using the Firebase Admin SDK. The Firebase UID is
 *   the canonical identity source; the `admin: true` custom claim is
 *   the authoritative grant for admin access.
 *
 *   A legacy session-based path remains active for backward-compatible
 *   development tooling.  The development-only x-dev-user header is
 *   accepted only in non-production environments.
 */

import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';

import type { AppConfig } from '../config.js';
import { parseBearerToken, type AuthService } from './auth-service.js';
import type { FirebaseIdTokenVerifier } from './firebase-id-token-verifier.js';
import { AppError } from './errors.js';
import {
  SUBSCRIPTION_ENTITLEMENTS,
  USER_ROLES,
  USER_STATUSES,
  canAccessAdminFeatures,
  canAccessMemberFeatures,
  isSuspendedStatus,
} from '@carcommunity/shared/users';
import type { SubscriptionEntitlement, UserRole, UserStatus } from '@carcommunity/shared/users';
import type { AuthenticatedUserSummary } from '@carcommunity/shared/auth';

export interface AuthContext {
  /** Stable user identifier from the backend database. Never use email as primary identifier. */
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  user: AuthenticatedUserSummary;
  /** Backend session identifier. */
  sessionId: string;
  sessionExpiresAt: string;
  lastActiveAt: string | null;
  /** ISO 8601 timestamp when onboarding was completed, or null if not yet done. */
  onboardingCompletedAt: string | null;
  /**
   * Firebase UID extracted from a verified Firebase ID token.
   * Present only when the request was authenticated via Firebase.
   * Never sourced from client-supplied request body or URL parameters.
   */
  firebaseUid?: string;
  /**
   * Whether the user holds the `admin: true` Firebase custom claim.
   * This claim is set exclusively by trusted backend code and cannot
   * be assigned by clients.
   * Present only when the request was authenticated via Firebase.
   */
  isFirebaseAdmin?: boolean;
}

declare module 'fastify' {
  interface FastifyRequest {
    auth: AuthContext | null;
  }
}

/**
 * Development-only header name for injecting a fake auth context in non-production environments.
 * This header is silently ignored in production.
 */
const DEV_AUTH_HEADER = 'x-dev-user';

const devAuthContextSchema = z.object({
  userId: z.string().min(1),
  role: z.enum(USER_ROLES),
  status: z.enum(USER_STATUSES),
  subscriptionEntitlement: z.enum(SUBSCRIPTION_ENTITLEMENTS),
  sessionId: z.string().min(1),
  onboardingCompletedAt: z.string().nullable().optional(),
});
type DevAuthContext = z.infer<typeof devAuthContextSchema>;

function parseDevAuthContext(value: string): DevAuthContext | null {
  try {
    const result = devAuthContextSchema.safeParse(JSON.parse(value) as unknown);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/**
 * Registers the auth context decoration on FastifyRequest and an onRequest hook
 * that populates `request.auth` from a verified token.
 *
 * Auth resolution order:
 * 1. If `firebaseIdTokenVerifier` is provided and an `Authorization: Bearer <token>` header is present,
 *    attempt Firebase ID token verification. On success, look up or create the
 *    user by Firebase UID and populate request.auth including the `admin` custom
 *    claim. On failure the token is rejected and request.auth is left null —
 *    there is no fallback to the legacy session path.
 * 2. If `firebaseIdTokenVerifier` is not provided, fall back to legacy
 *    session-based lookup via `authService.lookupSession`.
 * 3. In non-production environments only, a second hook (registered at
 *    startup exclusively when `config.isProduction` is false) accepts the
 *    `x-dev-user` header as a convenience for local development and testing.
 *    In production that hook does not exist at all — there is no runtime
 *    branch on the header.
 *
 * Rate limiting is applied globally by the `@fastify/rate-limit` plugin
 * registered in `registerSecurity` before this hook runs.
 */
export async function registerAuthContext(
  app: FastifyInstance,
  config: AppConfig,
  authService: AuthService,
  firebaseIdTokenVerifier?: FirebaseIdTokenVerifier,
): Promise<void> {
  app.decorateRequest('auth', null);

  // Requests that presented an Authorization header — a token auth attempt,
  // whether it parsed to a valid token or not (e.g. `Bearer`, `Bearer ` and
  // other malformed values parse to null). The non-production dev-header hook
  // below consults this so it never injects auth for a client that already
  // tried to authenticate; a failed attempt must not silently fall through to
  // the dev header.
  const requestsWithAuthorizationHeader = new WeakSet<FastifyRequest>();

  app.addHook('onRequest', async (request) => {
    const authorizationHeader = request.headers.authorization;
    if (typeof authorizationHeader === 'string' && authorizationHeader.trim() !== '') {
      requestsWithAuthorizationHeader.add(request);
    }
    const token = typeof authorizationHeader === 'string' ? parseBearerToken(authorizationHeader) : null;

    if (token) {
      // --- Firebase ID token path (preferred in production) ---
      if (firebaseIdTokenVerifier) {
        try {
          const decoded = await firebaseIdTokenVerifier.verifyIdToken(token);

          // Look up or create the backend user record by Firebase UID.
          // The Firebase UID is the authoritative identity source; it is never
          // sourced from client-supplied request parameters.
          const userSummary = await authService.findOrCreateUserByFirebaseUid(
            decoded.uid,
            decoded.email,
          );

          request.auth = {
            userId: userSummary.userId,
            // When the Firebase `admin: true` custom claim is present, use
            // 'admin' as the effective role so that legacy role-based checks
            // (e.g. canAccessAdminFeatures) remain consistent with the
            // claim-based authorization enforced in requireAdminHook.
            role: decoded.isAdmin ? 'admin' : (userSummary.roles[0] ?? 'user'),
            status: userSummary.status,
            subscriptionEntitlement: userSummary.subscriptionEntitlement,
            user: userSummary,
            sessionId: `firebase:${decoded.uid}`,
            sessionExpiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
            lastActiveAt: null,
            onboardingCompletedAt: userSummary.onboardingCompletedAt ?? null,
            firebaseUid: decoded.uid,
            isFirebaseAdmin: decoded.isAdmin,
          };
          return;
        } catch {
          // Invalid Firebase token — leave auth null; protected routes will reject below.
          return;
        }
      }

      // --- Legacy session path (development and backward compatibility) ---
      const session = await authService.lookupSession(token);
      if (session) {
        request.auth = {
          userId: session.userId,
          role: session.role,
          status: session.status,
          subscriptionEntitlement: session.subscriptionEntitlement,
          user: session.user,
          sessionId: session.sessionId,
          sessionExpiresAt: session.expiresAt.toISOString(),
          lastActiveAt: session.lastActiveAt ? session.lastActiveAt.toISOString() : null,
          onboardingCompletedAt: session.onboardingCompletedAt ? session.onboardingCompletedAt.toISOString() : null,
        };
        return;
      }
    }

    // Protected routes continue to require a valid backend session.
  });

  // TODO: Remove development header auth once all local/dev tooling uses real login.
  //
  // The x-dev-user header is a development-only convenience. This hook is
  // registered ONLY when config.isProduction is false — the decision is made
  // once here at startup from server config, never from request data, so in
  // production the hook (and any code path from the header to an auth context)
  // does not exist at all. It is also a separate handler from the token path
  // above, so even in development it can never be used to bypass token auth.
  if (!config.isProduction) {
    app.addHook('onRequest', async (request) => {
      // Never override a real auth context, and never consult the dev header
      // when the request presented an Authorization header — even a malformed
      // or rejected one. A failed token attempt leaves the request
      // unauthenticated, matching the Firebase path's behaviour and
      // deliberately stricter than the old legacy-session path, which fell
      // back to the dev header after a failed session lookup.
      if (request.auth || requestsWithAuthorizationHeader.has(request)) {
        return;
      }

      const devHeader = request.headers[DEV_AUTH_HEADER];
      if (typeof devHeader === 'string') {
        const parsed = parseDevAuthContext(devHeader);
        request.auth = parsed
          ? {
              ...parsed,
              user: {
                userId: parsed.userId,
                displayName: null,
                identities: [],
                roles: [parsed.role],
                status: parsed.status,
                subscriptionEntitlement: parsed.subscriptionEntitlement,
                onboardingCompletedAt: parsed.onboardingCompletedAt ?? null,
              },
              sessionExpiresAt: new Date(Date.now() + 60_000).toISOString(),
              lastActiveAt: null,
              onboardingCompletedAt: parsed.onboardingCompletedAt ?? null,
            }
          : null;
      }
    });
  }
}

/**
 * Fastify preHandler hook: allows unauthenticated requests; populates auth context if present.
 * Useful for routes that serve public data but can enhance responses for authenticated users.
 */
export async function optionalAuthHook(_request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  // Auth context is already populated (or null) by the onRequest hook in registerAuthContext.
}

/**
 * Fastify preHandler hook: requires a valid auth context.
 * Throws 401 if the request is unauthenticated.
 */
export async function requireAuthHook(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  if (!request.auth) {
    throw new AppError(401, 'unauthenticated', 'Authentication required.');
  }
  if (request.auth.status === 'deleted') {
    throw new AppError(403, 'forbidden', 'Your account has been deleted.');
  }
  if (isSuspendedStatus(request.auth.status)) {
    throw new AppError(403, 'suspended', 'Your account has been suspended.');
  }
}

/**
 * Fastify preHandler hook: requires an authenticated backend session only.
 * This is used for safety/privacy actions that must remain available even if
 * feature access has been lost.
 */
export async function requireAuthenticatedHook(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  if (!request.auth) {
    throw new AppError(401, 'unauthenticated', 'Authentication required.');
  }
}

/**
 * Fastify preHandler hook: requires admin or owner role.
 * Throws 401 if unauthenticated, 403 if authenticated but not admin or owner.
 *
 * For Firebase-authenticated requests the `admin: true` custom claim is the
 * authoritative grant; only trusted backend code can set custom claims.
 * For legacy session-authenticated requests the database role is used.
 */
export async function requireAdminHook(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  if (!request.auth) {
    throw new AppError(401, 'unauthenticated', 'Authentication required.');
  }
  if (isSuspendedStatus(request.auth.status)) {
    throw new AppError(403, 'suspended', 'Your account has been suspended.');
  }
  if (request.auth.status === 'deleted') {
    throw new AppError(403, 'forbidden', 'Your account has been deleted.');
  }

  if (request.auth.firebaseUid !== undefined) {
    // Firebase-authenticated path: require the `admin: true` custom claim.
    // Custom claims can only be set by trusted backend code via Firebase Admin SDK.
    // Clients cannot forge them because they cannot produce a Firebase-signed token.
    if (!request.auth.isFirebaseAdmin) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }
    return;
  }

  // Legacy session path: fall back to the database role check.
  if (!canAccessAdminFeatures({ role: request.auth.role, status: request.auth.status })) {
    throw new AppError(403, 'forbidden', 'Admin access required.');
  }
}

/**
 * Fastify preHandler hook: requires an active member_monthly subscription.
 * Throws 401 if unauthenticated, 403 with code 'suspended' if suspended, 403 if no member subscription.
 */
export async function requireMemberHook(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  if (!request.auth) {
    throw new AppError(401, 'unauthenticated', 'Authentication required.');
  }
  if (request.auth.status === 'deleted') {
    throw new AppError(403, 'forbidden', 'Your account has been deleted.');
  }
  if (isSuspendedStatus(request.auth.status)) {
    throw new AppError(403, 'suspended', 'Your account has been suspended.');
  }
  if (!canAccessMemberFeatures(request.auth)) {
    throw new AppError(403, 'forbidden', 'Member subscription required.');
  }
}
