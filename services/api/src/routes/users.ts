import type { FastifyInstance, FastifyReply } from 'fastify';
import { z } from 'zod';
import type { CurrentUserResponse } from '@carcommunity/shared/users';
import type { UserProfileResponse, PrivacySettingsResponse } from '@carcommunity/shared/onboarding';

import { requireAdminHook, requireAuthHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import {
  createUserService,
  DISPLAY_NAME_VALIDATION,
  type UserService,
} from '../lib/user-service.js';

const profileUpdateSchema = z
  .object({
    displayName: z.string().max(DISPLAY_NAME_VALIDATION.maxLength).nullable().optional(),
    ageConfirmed: z.boolean().optional(),
    termsAccepted: z.boolean().optional(),
    privacyPolicyAccepted: z.boolean().optional(),
  })
  .strict();

const privacySettingsUpdateSchema = z
  .object({
    anonymousPartnerStatsOptIn: z.boolean(),
  })
  .strict();

function toOnboardingData(profile: {
  onboardingCompletedAt: Date | null;
  ageConfirmedAt: Date | null;
  termsAcceptedAt: Date | null;
  privacyPolicyAcceptedAt: Date | null;
}) {
  return {
    onboardingCompletedAt: profile.onboardingCompletedAt?.toISOString() ?? null,
    ageConfirmedAt: profile.ageConfirmedAt?.toISOString() ?? null,
    termsAcceptedAt: profile.termsAcceptedAt?.toISOString() ?? null,
    privacyPolicyAcceptedAt: profile.privacyPolicyAcceptedAt?.toISOString() ?? null,
  };
}

export async function registerUserRoutes(
  app: FastifyInstance,
  dependencies: { userService?: UserService } = {},
): Promise<void> {
  const userService = dependencies.userService ?? createUserService(app.prisma);

  // ---------------------------------------------------------------------------
  // GET /v1/users/me — safe profile summary including onboarding status
  // ---------------------------------------------------------------------------
  app.get(
    '/v1/users/me',
    { preHandler: requireAuthHook },
    async (request, reply: FastifyReply): Promise<void> => {
      const auth = request.auth!;

      // Fetch full profile from DB to include onboarding timestamps.
      const profile = await userService.getUserProfile(auth.userId);

      await reply.code(200).send({
        ok: true,
        data: {
          user: {
            id: auth.userId,
            displayName: auth.user.displayName ?? null,
            role: auth.role,
            status: auth.status,
            subscriptionEntitlement: auth.subscriptionEntitlement,
            lastActiveAt: auth.lastActiveAt,
            onboarding: profile
              ? toOnboardingData(profile)
              : {
                  onboardingCompletedAt: auth.onboardingCompletedAt,
                  ageConfirmedAt: null,
                  termsAcceptedAt: null,
                  privacyPolicyAcceptedAt: null,
                },
          },
        },
      } satisfies CurrentUserResponse);
    },
  );

  // ---------------------------------------------------------------------------
  // PATCH /v1/users/me/profile — update display name / complete onboarding
  // ---------------------------------------------------------------------------
  app.patch(
    '/v1/users/me/profile',
    { preHandler: requireAuthHook },
    async (request, reply: FastifyReply): Promise<void> => {
      const auth = request.auth!;
      const body = profileUpdateSchema.parse(request.body);

      const updated = await userService.updateUserProfile({
        userId: auth.userId,
        ...body,
      });

      await reply.code(200).send({
        ok: true,
        data: {
          user: {
            id: updated.id,
            displayName: updated.displayName,
            role: updated.role,
            status: updated.status,
            subscriptionEntitlement: updated.subscriptionEntitlement,
            onboarding: toOnboardingData(updated),
          },
        },
      } satisfies UserProfileResponse);
    },
  );

  // ---------------------------------------------------------------------------
  // GET /v1/users/me/privacy-settings — current privacy preferences
  // ---------------------------------------------------------------------------
  app.get(
    '/v1/users/me/privacy-settings',
    { preHandler: requireAuthHook },
    async (request, reply: FastifyReply): Promise<void> => {
      const auth = request.auth!;
      const settings = await userService.getPrivacySettings(auth.userId);

      if (!settings) {
        throw new AppError(404, 'not_found', 'User not found.');
      }

      await reply.code(200).send({
        ok: true,
        data: {
          anonymousPartnerStatsOptIn: settings.anonymousPartnerStatsOptIn,
        },
      } satisfies PrivacySettingsResponse);
    },
  );

  // ---------------------------------------------------------------------------
  // PATCH /v1/users/me/privacy-settings — update privacy preferences
  // ---------------------------------------------------------------------------
  app.patch(
    '/v1/users/me/privacy-settings',
    { preHandler: requireAuthHook },
    async (request, reply: FastifyReply): Promise<void> => {
      const auth = request.auth!;
      const body = privacySettingsUpdateSchema.parse(request.body);

      const updated = await userService.updatePrivacySettings(
        auth.userId,
        body.anonymousPartnerStatsOptIn,
      );

      await reply.code(200).send({
        ok: true,
        data: {
          anonymousPartnerStatsOptIn: updated.anonymousPartnerStatsOptIn,
        },
      } satisfies PrivacySettingsResponse);
    },
  );

  // ---------------------------------------------------------------------------
  // GET /v1/admin/users — placeholder
  // ---------------------------------------------------------------------------
  app.get(
    '/v1/admin/users',
    { preHandler: requireAdminHook },
    async (_request, reply: FastifyReply): Promise<void> => {
      // TODO: Implement real admin users endpoint once backend admin authorisation exists.
      await reply.code(501).send({
        ok: false,
        error: {
          code: 'not_implemented',
          message: 'Admin users endpoint is not implemented until backend admin authorization exists.',
        },
      });
    },
  );
}
