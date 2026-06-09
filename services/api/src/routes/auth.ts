import {
  AUTH_PROVIDERS,
  AUTH_ROUTE_PATHS,
  type AuthResponse,
  type LogoutResponse,
} from '@carcommunity/shared/auth';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import type { AppConfig } from '../config.js';
import { parseBearerToken, type AuthService } from '../lib/auth-service.js';

const loginRequestSchema = z
  .object({
    provider: z.enum(AUTH_PROVIDERS),
    identityToken: z.string().min(1),
    providerSubject: z.string().min(1).optional(),
    platform: z.string().min(1).optional(),
    appVersion: z.string().min(1).optional(),
    buildNumber: z.string().min(1).optional(),
  })
  .strict();

const logoutRequestSchema = z.object({}).strict();

const notImplementedResponse = (
  message: string,
  code: Extract<AuthResponse, { ok: false }>['error']['code'] = 'provider_verification_not_implemented',
): AuthResponse => ({
  ok: false,
  error: {
    code,
    message,
  },
});

export async function registerAuthRoutes(
  app: FastifyInstance,
  config: AppConfig,
  authService: AuthService,
): Promise<void> {
  app.post(AUTH_ROUTE_PATHS.login, async (request, reply) => {
    const payload = loginRequestSchema.parse(request.body);

    // TODO: Verify Apple identity token (JWT signed by Apple) and extract stable providerSubject.
    // TODO: Verify Google identity token and extract stable providerSubject.
    // TODO: Validate identity-token issuer claim against provider allow-list.
    // TODO: Validate identity-token audience against configured app client IDs.
    // TODO: Validate nonce against a server-side login challenge to prevent replay.
    // TODO: Implement account linking when one user signs in with multiple providers.
    // TODO: Replace placeholder token with signed production JWT/session credentials.

    if (config.isProduction) {
      return reply.status(501).send(
        notImplementedResponse('Mobile provider verification is not implemented. Login is disabled in production.'),
      );
    }

    if (!payload.providerSubject) {
      return reply.status(400).send({
        ok: false,
        error: {
          code: 'validation_error',
          message:
            'providerSubject is required for development placeholder login until server-side provider verification is implemented.',
        },
      } satisfies AuthResponse);
    }

    const user = await authService.findOrCreateUserByProviderIdentity({
      provider: payload.provider,
      providerSubject: payload.providerSubject,
    });
    const session = await authService.createSession(user.userId);

    return reply.status(200).send({
      ok: true,
      data: {
        user,
        session: {
          sessionId: session.sessionId,
          expiresAt: session.expiresAt.toISOString(),
        },
        // Development-only placeholder token. Do not use in production.
        token: session.token,
      },
    } satisfies AuthResponse);
  });

  app.post(AUTH_ROUTE_PATHS.logout, async (request, reply) => {
    logoutRequestSchema.parse(request.body ?? {});

    const authorizationHeader = request.headers.authorization;
    const token = typeof authorizationHeader === 'string' ? parseBearerToken(authorizationHeader) : null;
    const revoked = token ? await authService.revokeSession(token) : false;

    return reply.status(200).send({
      ok: true,
      data: {
        revoked,
      },
    } satisfies LogoutResponse);
  });

  app.get(AUTH_ROUTE_PATHS.me, async (request, reply) => {
    if (!request.auth) {
      return reply.status(200).send({
        ok: false,
        error: {
          code: 'unauthenticated',
          message: 'No valid authenticated session.',
        },
      } satisfies AuthResponse);
    }

    return reply.status(200).send({
      ok: true,
      data: {
        user: request.auth.user,
        session: {
          sessionId: request.auth.sessionId,
          expiresAt: request.auth.sessionExpiresAt,
        },
      },
    } satisfies AuthResponse);
  });
}
