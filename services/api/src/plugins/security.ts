import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import type { FastifyInstance } from 'fastify';

import type { AppConfig } from '../config.js';

const LOCALHOST_ORIGIN_PATTERN = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/;

export async function registerSecurity(app: FastifyInstance, config: AppConfig): Promise<void> {
  await app.register(helmet, {
    global: true,
  });

  await app.register(cors, {
    credentials: false,
    origin(origin, callback) {
      if (!origin) {
        callback(null, true);
        return;
      }

      if (!config.isProduction && LOCALHOST_ORIGIN_PATTERN.test(origin)) {
        callback(null, true);
        return;
      }

      callback(null, false);
    },
  });

  await app.register(rateLimit, {
    global: true,
    max: 100,
    timeWindow: '1 minute',
  });
}
