import Fastify, { type FastifyInstance } from 'fastify';

import { type AppConfig, loadConfig } from './config.js';
import { registerAuthContext } from './lib/auth-context.js';
import { AppError, fromUnknownError } from './lib/errors.js';
import { registerPrisma } from './plugins/prisma.js';
import { registerSecurity } from './plugins/security.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerFeatureFlagRoutes } from './routes/feature-flags.js';
import { registerHealthRoutes } from './routes/health.js';
import { registerLiveLocationRoutes } from './routes/live-location.js';
import { registerUserRoutes } from './routes/users.js';
import { registerVersionRoutes } from './routes/version.js';

export async function createServer(config: AppConfig = loadConfig()): Promise<FastifyInstance> {
  const app = Fastify({
    logger: config.nodeEnv === 'test' ? false : { level: config.isProduction ? 'info' : 'debug' },
  });

  app.setErrorHandler((error, _request, reply) => {
    const appError = fromUnknownError(error);

    if (appError.statusCode >= 500) {
      app.log.error(error);
    }

    const payload: {
      ok: false;
      error: {
        code: string;
        message: string;
        details?: unknown;
      };
    } = {
      ok: false,
      error: {
        code: appError.code,
        message: appError.message,
      },
    };

    if (appError.details && appError.statusCode < 500) {
      payload.error.details = appError.details;
    }

    void reply.status(appError.statusCode).send(payload);
  });

  app.setNotFoundHandler((_request, _reply) => {
    throw new AppError(404, 'not_found', 'Route not found.');
  });

  await registerSecurity(app, config);
  await registerPrisma(app, config);
  await registerAuthContext(app, config);
  await registerHealthRoutes(app);
  await registerVersionRoutes(app);
  await registerAuthRoutes(app, config);
  await registerFeatureFlagRoutes(app);
  await registerLiveLocationRoutes(app);
  await registerUserRoutes(app);

  return app;
}
