/**
 * Placeholder auth context for backend request authorization.
 *
 * @remarks NOT PRODUCTION READY — this is a development-only placeholder.
 *   Real auth must verify tokens server-side and look up sessions from the database.
 *   Replace all TODOs before any production deployment.
 */

import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';

import type { AppConfig } from '../config.js';
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

export interface AuthContext {
  /** Stable user identifier from the backend database. Never use email as primary identifier. */
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  /** Backend session identifier. */
  sessionId: string;
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
});

function parseDevAuthContext(value: string): AuthContext | null {
  try {
    const result = devAuthContextSchema.safeParse(JSON.parse(value) as unknown);
    return result.success ? result.data : null;
  } catch {
    return null;
  }
}

/**
 * Registers the auth context decoration on FastifyRequest and an onRequest hook
 * that populates `request.auth` from a verified session.
 *
 * In non-production environments, a `X-Dev-User` header containing a JSON
 * AuthContext can be used to inject a fake auth context for local testing.
 * This header is silently ignored in production.
 *
 * Production safety: until real JWT verification is implemented, `request.auth`
 * will always be null in production. All routes protected by `requireAuthHook`,
 * `requireAdminHook`, or `requireMemberHook` will therefore return 401 —
 * locking down protected endpoints rather than accidentally opening them.
 *
 * Rate limiting is applied globally by the `@fastify/rate-limit` plugin
 * registered in `registerSecurity` before this hook runs.
 */
export async function registerAuthContext(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.decorateRequest('auth', null);

  app.addHook('onRequest', async (request) => {
    // TODO: Parse Authorization: ****** from request headers.
    // TODO: Verify Apple identity token (JWT signed by Apple).
    //   Validate issuer (https://appleid.apple.com) and audience (bundle ID).
    // TODO: Verify Google identity token.
    //   Validate issuer (accounts.google.com) and audience (OAuth client ID).
    // TODO: Look up session from the backend database by session ID.
    // TODO: Check token expiration and reject expired tokens.
    // TODO: Implement refresh token handling.
    // TODO: Validate role from database — never trust client-side role claims.

    if (!config.isProduction) {
      // Development-only: accept the X-Dev-User header to inject a fake auth context.
      // NEVER honour this header in production — the guard above ensures it is skipped.
      const devHeader = request.headers[DEV_AUTH_HEADER];
      if (typeof devHeader === 'string') {
        request.auth = parseDevAuthContext(devHeader);
      }
    }
    // In production, request.auth remains null until real token verification is
    // implemented. Protected routes will respond with 401 for all requests.
  });
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
}

/**
 * Fastify preHandler hook: requires admin or owner role.
 * Throws 401 if unauthenticated, 403 if authenticated but not admin or owner.
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
