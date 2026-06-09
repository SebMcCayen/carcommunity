import type { FastifyInstance, FastifyReply } from 'fastify';
import type { CurrentUserResponse } from '@carcommunity/shared/users';

import { requireAdminHook, requireAuthHook } from '../lib/auth-context.js';

export async function registerUserRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/users/me', { preHandler: requireAuthHook }, async (request, reply: FastifyReply): Promise<void> => {
    const auth = request.auth;
    if (!auth) {
      await reply.code(401).send({
        ok: false,
        error: {
          code: 'unauthenticated',
          message: 'Authentication required.',
        },
      });
      return;
    }

    await reply.code(200).send({
      ok: true,
      data: {
        user: {
          id: auth.userId,
          displayName: auth.user.displayName ?? null,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
          lastActiveAt: auth.lastActiveAt,
        },
      },
    } satisfies CurrentUserResponse);
  });

  app.get('/v1/admin/users', { preHandler: requireAdminHook }, async (_request, reply: FastifyReply): Promise<void> => {
    // TODO: Implement real admin users endpoint once backend admin authorisation exists.
    await reply.code(501).send({
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'Admin users endpoint is not implemented until backend admin authorization exists.',
      },
    });
  });
}
