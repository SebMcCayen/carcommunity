import type { FastifyInstance } from 'fastify';

import { API_NAME } from '../config.js';

type HealthzResponse = { ok: true; data: { status: 'alive' } };

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

  app.get('/readyz', async (_request, reply) => {
    // Placeholder: no dependency checks yet. Extend with real checks (e.g. DB ping) as needed.
    // When checks is empty, Array.prototype.every returns true (vacuous truth), so the endpoint
    // reports ready until concrete dependency checks are added. This is intentional for the
    // placeholder — real readiness gates will be wired in before production launch.
    const checks: Record<string, 'ok' | 'error'> = {};
    const ready = Object.values(checks).every((v) => v === 'ok');

    await reply.code(ready ? 200 : 503).send({
      ok: ready,
      data: {
        status: ready ? ('ready' as const) : ('not_ready' as const),
        checks,
      },
    });
  });
}
