import Fastify, { type FastifyInstance } from 'fastify';

import { type AppConfig, loadConfig, resolveAuthVerificationConfig } from './config.js';
import { createAuthProviderVerifier, type AuthProviderVerifier } from './lib/auth-provider-verifier.js';
import { createAuthService, type AuthService } from './lib/auth-service.js';
import { registerAuthContext } from './lib/auth-context.js';
import { AppError, fromUnknownError } from './lib/errors.js';
import type { LiveLocationService } from './lib/live-location-service.js';
import type { EventService } from './lib/event-service.js';
import type { ModerationService } from './lib/moderation-service.js';
import { BlockingService } from './lib/blocking-service.js';
import { registerPrisma } from './plugins/prisma.js';
import { registerSecurity } from './plugins/security.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerBlockingRoutes } from './routes/blocking.js';
import { registerEventRoutes } from './routes/events.js';
import { registerFeatureFlagRoutes } from './routes/feature-flags.js';
import { registerHealthRoutes } from './routes/health.js';
import { registerLiveLocationRoutes } from './routes/live-location.js';
import { registerDiagnosticsRoutes } from './routes/diagnostics.js';
import { registerModerationRoutes } from './routes/moderation.js';
import { registerSubscriptionRoutes } from './routes/subscription.js';
import { registerUserRoutes } from './routes/users.js';
import { registerVersionRoutes } from './routes/version.js';
import { registerAppSettingsRoutes } from './routes/app-settings.js';
import type { UserService } from './lib/user-service.js';

export interface ServerDependencies {
  authService?: AuthService;
  authProviderVerifier?: AuthProviderVerifier;
  liveLocationService?: LiveLocationService;
  liveLocationFeatureEnabled?: boolean;
  eventService?: EventService;
  subscriptionService?: Pick<import('./lib/subscription-service.js').SubscriptionService, 'getSubscriptionForUser' | 'getAdminSubscriptionForUser'>;
  moderationService?: ModerationService;
  diagnosticsService?: import('./lib/diagnostics-service.js').DiagnosticsService;
  userService?: UserService;
  blockingService?: BlockingService;
}

export async function createServer(
  config: AppConfig = loadConfig(),
  dependencies: ServerDependencies = {},
): Promise<FastifyInstance> {
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
  const authService = dependencies.authService ?? createAuthService(app.prisma);
  const authProviderVerifier =
    dependencies.authProviderVerifier ??
    createAuthProviderVerifier({
      config: resolveAuthVerificationConfig(config).providers,
    });
  await registerAuthContext(app, config, authService);
  await registerHealthRoutes(app);
  await registerVersionRoutes(app);
  await registerAuthRoutes(app, config, authService, authProviderVerifier);
  await registerFeatureFlagRoutes(app);
  await registerLiveLocationRoutes(app, {
    liveLocationService: dependencies.liveLocationService,
    liveLocationFeatureEnabled: dependencies.liveLocationFeatureEnabled,
    blockingService: dependencies.blockingService,
  });
  await registerEventRoutes(app, {
    eventService: dependencies.eventService,
  });
  await registerUserRoutes(app, { userService: dependencies.userService });
  await registerBlockingRoutes(app, { blockingService: dependencies.blockingService });
  await registerSubscriptionRoutes(app, {
    subscriptionService: dependencies.subscriptionService,
  });
  await registerModerationRoutes(app, {
    moderationService: dependencies.moderationService,
  });
  await registerDiagnosticsRoutes(app, {
    diagnosticsService: dependencies.diagnosticsService,
  });
  await registerAppSettingsRoutes(app);

  return app;
}
