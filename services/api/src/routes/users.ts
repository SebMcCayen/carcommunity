import type { FastifyInstance, FastifyReply } from 'fastify';

export async function registerUserRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/users/me', async (_request, reply: FastifyReply): Promise<void> => {
    await reply.code(501).send({
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'User profile endpoint is not implemented until backend sessions exist.',
      },
    });
  });

  app.get('/v1/admin/users', async (_request, reply: FastifyReply): Promise<void> => {
    await reply.code(501).send({
      ok: false,
      error: {
        code: 'not_implemented',
        message: 'Admin users endpoint is not implemented until backend admin authorization exists.',
      },
    });
  });
}
