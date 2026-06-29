import Fastify, { type FastifyInstance } from 'fastify';

import { type AppConfig, loadConfig, resolveAuthVerificationConfig } from './config.js';
import { createAuthProviderVerifier, type AuthProviderVerifier } from './lib/auth-provider-verifier.js';
import { createAuthService, type AuthService } from './lib/auth-service.js';
import { registerAuthContext } from './lib/auth-context.js';
import { getFirebaseAdminAuth } from './lib/firebase-admin.js';
import { createFirebaseIdTokenVerifier, type FirebaseIdTokenVerifier } from './lib/firebase-id-token-verifier.js';
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
import { registerPartnerOfferRoutes } from './routes/partner-offers.js';
import { registerPartnerInsightsRoutes } from './routes/partner-insights.js';
import { PartnerOfferService } from './lib/partner-offer-service.js';
import { PartnerInsightsService } from './lib/partner-insights-service.js';
import { registerDigitalBillboardRoutes } from './routes/digital-billboards.js';
import { registerNotificationRoutes } from './routes/notifications.js';
import { registerAdminNotificationRoutes } from './routes/admin-notifications.js';

export interface ServerDependencies {
  authService?: AuthService;
  authProviderVerifier?: AuthProviderVerifier;
  firebaseIdTokenVerifier?: FirebaseIdTokenVerifier;
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
  partnerOfferService?: PartnerOfferService;
  partnerInsightsService?: PartnerInsightsService;
  billboardService?: import('./lib/billboard-service.js').BillboardService;
  notificationService?: import('./lib/notification-service.js').NotificationService;
  notificationDeliveryService?: import('./lib/notification-delivery-service.js').NotificationDeliveryService;
  pushNotificationsFeatureEnabled?: boolean;
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
  const firebaseIdTokenVerifier: FirebaseIdTokenVerifier | undefined =
    dependencies.firebaseIdTokenVerifier ??
    (config.firebaseProjectId
      ? createFirebaseIdTokenVerifier(getFirebaseAdminAuth(config.firebaseProjectId))
      : undefined);
  const badgeService =
    dependencies.badgeService ?? new BadgeService(app.prisma, config.earlyMemberCutoffDate);
  const partnerInsightsService =
    dependencies.partnerInsightsService ?? new PartnerInsightsService(app.prisma, config);
  await registerAuthContext(app, config, authService, firebaseIdTokenVerifier);
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
  await registerPartnerOfferRoutes(app, {
    partnerOfferService: dependencies.partnerOfferService,
  });
  await registerPartnerInsightsRoutes(app, {
    partnerInsightsService,
    config,
  });
  await registerDigitalBillboardRoutes(app, {
    billboardService: dependencies.billboardService,
    partnerInsightsService,
  });
  await registerNotificationRoutes(app, {
    notificationService: dependencies.notificationService,
    pushNotificationsFeatureEnabled: dependencies.pushNotificationsFeatureEnabled,
  });
  await registerAdminNotificationRoutes(app, {
    notificationService: dependencies.notificationService,
    deliveryService: dependencies.notificationDeliveryService,
    moderationService: dependencies.moderationService,
    pushNotificationsFeatureEnabled: dependencies.pushNotificationsFeatureEnabled,
  });

  return app;
}
