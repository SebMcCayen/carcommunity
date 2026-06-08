import { AUTH_PROVIDERS, AUTH_ROUTE_PATHS, type AuthResponse } from '@carcommunity/shared/auth';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import type { AppConfig } from '../config.js';

const mobileLoginRequestSchema = z
  .object({
    provider: z.enum(AUTH_PROVIDERS),
    identityToken: z.string().min(1),
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

export async function registerAuthRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.post(AUTH_ROUTE_PATHS.mobileLogin, async (request, reply) => {
    mobileLoginRequestSchema.parse(request.body);

    // TODO: Verify Apple identity token and extract a stable provider subject.
    // TODO: Verify Google identity token and extract a stable provider subject.
    // TODO: Lookup existing user by provider subject or create user identity mapping.
    // TODO: Issue backend session/JWT only after provider verification succeeds.
    // TODO: Implement refresh token rotation and revocation strategy.
    const message = config.isProduction
      ? 'Mobile provider verification is not implemented. Login is disabled in production.'
      : 'Mobile provider verification is not implemented yet.';

    return reply.status(501).send(notImplementedResponse(message));
  });

  app.post(AUTH_ROUTE_PATHS.logout, async (request, reply) => {
    logoutRequestSchema.parse(request.body ?? {});

    // TODO: Revoke active session/refresh tokens in backend session store.
    return reply
      .status(501)
      .send(notImplementedResponse('Logout is not implemented until backend session management exists.', 'not_implemented'));
  });

  app.get(AUTH_ROUTE_PATHS.me, async (_request, reply) => {
    return reply
      .status(501)
      .send(notImplementedResponse('Current user endpoint is not implemented until backend sessions exist.', 'not_implemented'));
  });
}
