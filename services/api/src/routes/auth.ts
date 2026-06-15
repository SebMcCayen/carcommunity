import {
  AUTH_PROVIDERS,
  AUTH_ROUTE_PATHS,
  type AuthResponse,
  type LogoutResponse,
} from '@carcommunity/shared/auth';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { type AppConfig, resolveAuthVerificationConfig } from '../config.js';
import type { AuthProviderVerifier } from '../lib/auth-provider-verifier.js';
import { parseBearerToken, type AuthService } from '../lib/auth-service.js';
import { AppError } from '../lib/errors.js';

const loginRequestSchema = z
  .object({
    provider: z.enum(AUTH_PROVIDERS),
    identityToken: z.string().min(1),
    providerSubject: z.string().min(1).optional(),
    nonce: z.string().min(1).optional(),
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
  authProviderVerifier: AuthProviderVerifier,
): Promise<void> {
  const authVerification = resolveAuthVerificationConfig(config);

  app.post(AUTH_ROUTE_PATHS.login, async (request, reply) => {
    const payload = loginRequestSchema.parse(request.body);

    if (config.isProduction && authVerification.mode !== 'strict') {
      return reply.status(501).send(
        notImplementedResponse('Mobile provider verification is not implemented. Login is disabled in production.'),
      );
    }

    let providerSubject = payload.providerSubject;
    let providerEmail: string | null | undefined;

    if (authVerification.mode === 'strict') {
      const verifiedIdentity = await authProviderVerifier.verifyIdentityToken({
        provider: payload.provider,
        identityToken: payload.identityToken,
        nonce: payload.nonce,
      });

      if (verifiedIdentity.provider !== payload.provider) {
        throw new AppError(401, 'invalid_identity_provider', 'Identity token provider does not match the requested provider.');
      }

      providerSubject = verifiedIdentity.providerSubject;
      providerEmail = verifiedIdentity.email;
    } else {
      // TODO: Remove client-provided providerSubject fallback before enabling production mobile auth.
      if (!providerSubject) {
        throw new AppError(
          400,
          'validation_error',
          'providerSubject is required for development placeholder login until strict provider verification is enabled.',
        );
      }
    }

    const user = await authService.findOrCreateUserByProviderIdentity({
      provider: payload.provider,
      providerSubject,
      providerEmail,
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
        // TODO: Replace the development-only session token with production credentials before release.
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
