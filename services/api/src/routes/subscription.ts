import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import {
  SUBSCRIPTION_ROUTE_PATHS,
  buildAdminUserSubscriptionPath,
  type AdminUserSubscriptionResponse,
  type CurrentEntitlementResponse,
  type SubscriptionRefreshPlaceholderResponse,
} from '@carcommunity/shared/subscription';

import { requireAdminHook, requireAuthHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import {
  SubscriptionService,
  buildAdminUserSubscriptionSummary,
} from '../lib/subscription-service.js';

const adminUserIdParamsSchema = z
  .object({
    userId: z.string().uuid(),
  })
  .strict();

export interface RegisterSubscriptionRoutesDependencies {
  subscriptionService?: Pick<SubscriptionService, 'getSubscriptionForUser' | 'getAdminSubscriptionForUser'>;
}

export async function registerSubscriptionRoutes(
  app: FastifyInstance,
  dependencies: RegisterSubscriptionRoutesDependencies = {},
): Promise<void> {
  const subscriptionService =
    dependencies.subscriptionService ?? new SubscriptionService(app.prisma);

  /**
   * GET /v1/subscription/me
   * Requires authenticated user.
   * Returns the current effective entitlement and a safe subscription summary.
   * Never exposes raw provider tokens or sensitive receipt data.
   */
  app.get(
    SUBSCRIPTION_ROUTE_PATHS.me,
    { preHandler: requireAuthHook },
    async (request): Promise<CurrentEntitlementResponse> => {
      const auth = request.auth!;
      const result = await subscriptionService.getSubscriptionForUser(auth.userId);

      return {
        ok: true,
        data: {
          entitlement: result.entitlement,
          subscription: result.subscription,
        },
      };
    },
  );

  /**
   * POST /v1/subscription/refresh-placeholder
   * Requires authenticated user.
   * Placeholder only — does not validate real receipts.
   *
   * TODO: Replace with real Apple App Store Server API receipt/notification validation.
   *   - Verify receipt with /verifyReceipt or App Store Server Notifications V2.
   *   - Validate signed JWS transactions using Apple's public keys.
   *   - Handle subscription renewal, expiry, and revocation server-side.
   *   - Never trust client-reported purchase state.
   *
   * TODO: Replace with real Google Play Billing purchase validation.
   *   - Call purchases.subscriptions.get from the Google Play Developer API.
   *   - Validate purchaseToken server-side via Google API.
   *   - Handle subscription state changes via Real-time Developer Notifications (RTDN).
   *   - Never trust client-reported purchase state.
   *
   * TODO: Add server-side entitlement update logic after receipt validation.
   *   - Update User.subscriptionEntitlement after verifying a valid active subscription.
   *   - Create or update a SubscriptionRecord with the verified data.
   *   - Apply hash before storing any purchase token identifier.
   *
   * TODO: Add webhook/server notification handling endpoint.
   *   - Apple: App Store Server Notifications V2 (signed JWS payloads).
   *   - Google: Real-time Developer Notifications via Pub/Sub.
   *   - Validate signatures before processing any notification.
   *
   * TODO: Add fraud handling.
   *   - Detect and reject duplicate transaction IDs.
   *   - Rate-limit refresh requests per user.
   *   - Alert on suspicious patterns.
   */
  app.post(
    SUBSCRIPTION_ROUTE_PATHS.refreshPlaceholder,
    { preHandler: requireAuthHook },
    async (_request): Promise<SubscriptionRefreshPlaceholderResponse> => {
      return {
        ok: true,
        data: {
          _placeholder: true,
          message:
            'Subscription refresh is not yet implemented. Purchase validation requires Apple App Store or Google Play integration.',
        },
      };
    },
  );

  /**
   * GET /v1/admin/users/:userId/subscription
   * Requires admin or owner role.
   * Returns a safe subscription summary for the selected user.
   * Never exposes raw provider tokens or sensitive receipt data.
   *
   * TODO: Add subscription revoke/cancel admin action.
   *   - Revoke must follow Apple and Google refund/revoke rules.
   *   - Apple: refunds are initiated via App Store — backend cannot unilaterally refund.
   *   - Google: refunds via Google Play refund API; cancel via purchases.subscriptions.cancel.
   *   - Both require human review, reason, and audit logging before execution.
   *
   * TODO: Add audit logging for all subscription-related admin actions.
   *   - Log actor, target user, action type, and reason for every change.
   *   - Audit log must be append-only and tamper-evident.
   *
   * TODO: Add support workflow for suspended users with active subscriptions.
   *   - Admin should be able to see and handle cases where a suspended user still has
   *     an active subscription (isSuspendedWithActiveSubscription flag).
   *   - Define a clear process for communicating with the subscriber before or after action.
   */
  app.get(
    buildAdminUserSubscriptionPath(':userId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminUserSubscriptionResponse> => {
      const params = adminUserIdParamsSchema.parse(request.params);

      const result = await subscriptionService.getAdminSubscriptionForUser(params.userId);

      if (!result) {
        throw new AppError(404, 'not_found', 'User not found.');
      }

      return {
        ok: true,
        data: {
          subscription: buildAdminUserSubscriptionSummary(result),
        },
      };
    },
  );
}
