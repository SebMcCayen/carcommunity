import Fastify, { type FastifyInstance } from 'fastify';

import { type AppConfig, loadConfig, resolveAuthVerificationConfig } from './config.js';
import { createAuthProviderVerifier, type AuthProviderVerifier } from './lib/auth-provider-verifier.js';
import { createAuthService, type AuthService } from './lib/auth-service.js';
import { registerAuthContext } from './lib/auth-context.js';
import { AppError, fromUnknownError } from './lib/errors.js';
import type { LiveLocationService } from './lib/live-location-service.js';
import type { EventService } from './lib/event-service.js';
import type { ModerationService } from './lib/moderation-service.js';
import type { BlockingService } from './lib/blocking-service.js';
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
import { registerEventChatRoutes } from './routes/event-chat.js';
import { registerGroupDriveRoutes } from './routes/group-drive.js';
import type { EventChatService } from './lib/event-chat-service.js';
import type { UserService } from './lib/user-service.js';
import { registerSavedDrivesRoutes } from './routes/saved-drives.js';
import { registerGarageRoutes } from './routes/garage.js';
import { registerBadgeRoutes } from './routes/badges.js';
import { BadgeService } from './lib/badge-service.js';
import { registerPointsRoutes } from './routes/points.js';
import { registerCrownHuntRoutes } from './routes/crown-hunt.js';
import { registerPartnerRoutes } from './routes/partners.js';

export interface ServerDependencies {
  authService?: AuthService;
  authProviderVerifier?: AuthProviderVerifier;
  liveLocationService?: LiveLocationService;
  liveLocationFeatureEnabled?: boolean;
  eventService?: EventService;
  eventChatService?: EventChatService;
  groupDriveService?: import('./lib/group-drive-service.js').GroupDriveService;
  subscriptionService?: Pick<import('./lib/subscription-service.js').SubscriptionService, 'getSubscriptionForUser' | 'getAdminSubscriptionForUser'>;
  moderationService?: ModerationService;
  diagnosticsService?: import('./lib/diagnostics-service.js').DiagnosticsService;
  userService?: UserService;
  blockingService?: BlockingService;
  savedDriveService?: import('./lib/saved-drive-service.js').SavedDriveService;
  garageService?: import('./lib/garage-service.js').GarageService;
  badgeService?: BadgeService;
  pointsService?: import('./lib/points-service.js').PointsService;
  crownHuntService?: import('./lib/crown-hunt-service.js').CrownHuntService;
  partnerApplicationService?: import('./lib/partner-application-service.js').PartnerApplicationService;
  partnerCompanyService?: import('./lib/partner-company-service.js').PartnerCompanyService;
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
  const badgeService =
    dependencies.badgeService ?? new BadgeService(app.prisma, config.earlyMemberCutoffDate);
  await registerAuthContext(app, config, authService);
  await registerHealthRoutes(app);
  await registerVersionRoutes(app);
  await registerAuthRoutes(app, config, authService, authProviderVerifier, badgeService);
  await registerFeatureFlagRoutes(app);
  await registerLiveLocationRoutes(app, {
    liveLocationService: dependencies.liveLocationService,
    liveLocationFeatureEnabled: dependencies.liveLocationFeatureEnabled,
    blockingService: dependencies.blockingService,
  });
  await registerEventRoutes(app, {
    eventService: dependencies.eventService,
    badgeService,
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
  await registerEventChatRoutes(app, {
    eventChatService: dependencies.eventChatService,
  });
  await registerGroupDriveRoutes(app, {
    groupDriveService: dependencies.groupDriveService,
    blockingService: dependencies.blockingService,
  });
  await registerSavedDrivesRoutes(app, {
    savedDriveService: dependencies.savedDriveService,
  });
  await registerGarageRoutes(app, {
    garageService: dependencies.garageService,
    badgeService,
  });
  await registerBadgeRoutes(app, {
    badgeService,
  });
  await registerPointsRoutes(app, {
    pointsService: dependencies.pointsService,
  });
  await registerCrownHuntRoutes(app, {
    crownHuntService: dependencies.crownHuntService,
  });
  await registerPartnerRoutes(app, {
    partnerApplicationService: dependencies.partnerApplicationService,
    partnerCompanyService: dependencies.partnerCompanyService,
  });

  return app;
}
