import type { FastifyInstance } from 'fastify';

import { API_NAME } from '../config.js';

export async function registerHealthRoutes(app: FastifyInstance): Promise<void> {
  app.get('/health', async () => ({
    ok: true,
    data: {
      service: API_NAME,
      status: 'ok',
    },
  }));
}
