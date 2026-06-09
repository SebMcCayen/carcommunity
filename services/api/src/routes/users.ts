import type { FastifyInstance, FastifyReply } from 'fastify';

import { requireAdminHook, requireAuthHook } from '../lib/auth-context.js';

export async function registerUserRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/users/me', { preHandler: requireAuthHook }, async (_request, reply: FastifyReply): Promise<void> => {
    // TODO: Implement real user profile endpoint once backend sessions exist.
    await reply.code(501).send({
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'User profile endpoint is not implemented until backend sessions exist.',
      },
    });
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
