import type { PrismaClient } from '@prisma/client';
import type { SubscriptionEntitlement, UserRole, UserStatus } from '@carcommunity/shared/users';

const DISPLAY_NAME_MAX_LENGTH = 120;

export const DISPLAY_NAME_VALIDATION = {
  maxLength: DISPLAY_NAME_MAX_LENGTH,
} as const;

export interface UserProfileRecord {
  id: string;
  displayName: string | null;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  onboardingCompletedAt: Date | null;
  ageConfirmedAt: Date | null;
  termsAcceptedAt: Date | null;
  privacyPolicyAcceptedAt: Date | null;
  anonymousPartnerStatsOptIn: boolean;
}

export interface UserProfileUpdateInput {
  userId: string;
  /** Optional display name. Pass null to clear. */
  displayName?: string | null;
  /** Set true to record age confirmation. Has no effect if already confirmed. */
  ageConfirmed?: boolean;
  /** Set true to record terms acceptance. Has no effect if already accepted. */
  termsAccepted?: boolean;
  /** Set true to record privacy policy acceptance. Has no effect if already accepted. */
  privacyPolicyAccepted?: boolean;
}

export interface UserService {
  getUserProfile(userId: string): Promise<UserProfileRecord | null>;
  updateUserProfile(input: UserProfileUpdateInput): Promise<UserProfileRecord>;
  getPrivacySettings(userId: string): Promise<{ anonymousPartnerStatsOptIn: boolean } | null>;
  updatePrivacySettings(
    userId: string,
    anonymousPartnerStatsOptIn: boolean,
  ): Promise<{ anonymousPartnerStatsOptIn: boolean }>;
}

const USER_PROFILE_SELECT = {
  id: true,
  displayName: true,
  role: true,
  status: true,
  subscriptionEntitlement: true,
  onboardingCompletedAt: true,
  ageConfirmedAt: true,
  termsAcceptedAt: true,
  privacyPolicyAcceptedAt: true,
  anonymousPartnerStatsOptIn: true,
} as const;

export function createUserService(prisma: PrismaClient): UserService {
  return {
    async getUserProfile(userId) {
      const user = await prisma.user.findUnique({
        where: { id: userId },
        select: USER_PROFILE_SELECT,
      });
      return user as UserProfileRecord | null;
    },

    async updateUserProfile(input) {
      const now = new Date();
      const updateData: Record<string, unknown> = {};

      if ('displayName' in input) {
        updateData.displayName = input.displayName;
      }

      // Only set confirmation timestamps when they are not already recorded,
      // to preserve the original acceptance timestamp for auditing/compliance.
      if (input.ageConfirmed === true || input.termsAccepted === true || input.privacyPolicyAccepted === true) {
        const current = await prisma.user.findUnique({
          where: { id: input.userId },
          select: {
            ageConfirmedAt: true,
            termsAcceptedAt: true,
            privacyPolicyAcceptedAt: true,
          },
        });
        if (input.ageConfirmed === true && !current?.ageConfirmedAt) {
          updateData.ageConfirmedAt = now;
        }
        if (input.termsAccepted === true && !current?.termsAcceptedAt) {
          updateData.termsAcceptedAt = now;
        }
        if (input.privacyPolicyAccepted === true && !current?.privacyPolicyAcceptedAt) {
          updateData.privacyPolicyAcceptedAt = now;
        }
      }

      let updated = await prisma.user.update({
        where: { id: input.userId },
        data: updateData,
        select: USER_PROFILE_SELECT,
      });

      // Auto-complete onboarding when all three required confirmations are set.
      // Use the latest of the three timestamps to preserve the original acceptance dates.
      if (
        !updated.onboardingCompletedAt &&
        updated.ageConfirmedAt &&
        updated.termsAcceptedAt &&
        updated.privacyPolicyAcceptedAt
      ) {
        const completedAt = new Date(
          Math.max(
            updated.ageConfirmedAt.getTime(),
            updated.termsAcceptedAt.getTime(),
            updated.privacyPolicyAcceptedAt.getTime(),
          ),
        );
        updated = await prisma.user.update({
          where: { id: input.userId },
          data: { onboardingCompletedAt: completedAt },
          select: USER_PROFILE_SELECT,
        });
      }

      return updated as UserProfileRecord;
    },

    async getPrivacySettings(userId) {
      const user = await prisma.user.findUnique({
        where: { id: userId },
        select: { anonymousPartnerStatsOptIn: true },
      });
      if (!user) return null;
      return { anonymousPartnerStatsOptIn: user.anonymousPartnerStatsOptIn };
    },

    async updatePrivacySettings(userId, anonymousPartnerStatsOptIn) {
      await prisma.user.update({
        where: { id: userId },
        data: { anonymousPartnerStatsOptIn },
      });
      return { anonymousPartnerStatsOptIn };
    },
  };
}
