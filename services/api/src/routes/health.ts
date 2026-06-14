import type { FastifyInstance, FastifyReply } from 'fastify';

import { API_NAME } from '../config.js';

type HealthzResponse = { ok: true; data: { status: 'alive' } };
type ReadyzResponse =
  | { ok: true; data: { status: 'ready'; checks: Record<string, 'ok' | 'error'> } }
  | { ok: false; data: { status: 'not_ready'; checks: Record<string, 'ok' | 'error'> } };

export async function registerHealthRoutes(app: FastifyInstance): Promise<void> {
  app.get('/health', async () => ({
    ok: true,
    data: {
      service: API_NAME,
      status: 'ok',
    },
  }));

  app.get('/healthz', async (): Promise<HealthzResponse> => ({
    ok: true,
    data: { status: 'alive' },
  }));

  app.get('/readyz', async (_request, reply: FastifyReply): Promise<ReadyzResponse> => {
    // Placeholder: extend with real dependency checks (e.g. database ping) as needed.
    const checks: Record<string, 'ok' | 'error'> = {};
    const ready = Object.values(checks).every((v) => v === 'ok');

    return reply.status(ready ? 200 : 503).send({
      ok: ready,
      data: {
        status: ready ? 'ready' : 'not_ready',
        checks,
      },
    }) as unknown as ReadyzResponse;
  });
}
