import type { FastifyInstance } from 'fastify';

import { API_NAME, API_VERSION } from '../config.js';

export async function registerVersionRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/version', async () => ({
    ok: true,
    data: {
      name: API_NAME,
      version: API_VERSION,
    },
  }));
}
