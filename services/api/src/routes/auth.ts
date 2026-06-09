import { AUTH_PROVIDERS, AUTH_ROUTE_PATHS, type AuthResponse } from '@carcommunity/shared/auth';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import type { AppConfig } from '../config.js';

const loginRequestSchema = z
  .object({
    provider: z.enum(AUTH_PROVIDERS),
    identityToken: z.string().min(1),
    // Optional fields — the backend will verify these server-side once token verification is implemented.
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

export async function registerAuthRoutes(app: FastifyInstance, config: AppConfig): Promise<void> {
  app.post(AUTH_ROUTE_PATHS.login, async (request, reply) => {
    loginRequestSchema.parse(request.body);

    // TODO: Verify Apple identity token (JWT signed by Apple) and extract stable providerSubject.
    //   Validate issuer (https://appleid.apple.com) and audience (bundle ID).
    //   Validate nonce if provided during sign-in flow.
    // TODO: Verify Google identity token and extract stable providerSubject.
    //   Validate issuer (accounts.google.com) and audience (OAuth client ID).
    // TODO: Look up UserIdentity by (provider, providerSubject); create User + UserIdentity on first login.
    // TODO: Implement account linking when the same user authenticates with multiple providers.
    // TODO: Issue a signed backend session/JWT only after provider verification succeeds.
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
    // TODO: Validate bearer token / session cookie from the Authorization header.
    // TODO: Look up authenticated user from the backend session store.
    // TODO: Return AuthenticatedUserSummary with userId, identities, and roles.
    return reply
      .status(501)
      .send(notImplementedResponse('Current user endpoint is not implemented until backend sessions exist.', 'not_implemented'));
  });
}
