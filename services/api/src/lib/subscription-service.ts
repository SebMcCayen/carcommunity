/**
 * Subscription service — backend source of truth for entitlement lookups.
 *
 * Security requirements:
 * - Never return raw purchase tokens in any response.
 * - Never store raw purchase tokens — only hashed values are permitted.
 * - Suspension and deleted status always override subscription entitlement.
 * - Backend enforces all access decisions independently of client state.
 */

import type { PrismaClient } from '@prisma/client';

import { isSuspendedStatus } from '@carcommunity/shared/users';
import type { SubscriptionEntitlement } from '@carcommunity/shared/users';
import {
  getEffectiveEntitlement,
  isSubscriptionActiveStatus,
} from '@carcommunity/shared/subscription';
import type {
  AdminUserSubscriptionSummary,
  SubscriptionSourceSummary,
} from '@carcommunity/shared/subscription';

export interface SubscriptionForUserResult {
  userId: string;
  entitlement: SubscriptionEntitlement;
  subscription: SubscriptionSourceSummary | null;
}

export interface AdminSubscriptionForUserResult {
  userId: string;
  entitlement: SubscriptionEntitlement;
  subscription: SubscriptionSourceSummary | null;
  isSuspendedWithActiveSubscription: boolean;
}

function mapRecordStatus(
  prismaStatus: string,
): import('@carcommunity/shared/subscription').SubscriptionStatus {
  // Map Prisma SubscriptionRecordStatus values to shared SubscriptionStatus.
  const valid = ['inactive', 'active', 'grace_period', 'expired', 'revoked', 'cancelled'] as const;
  if ((valid as readonly string[]).includes(prismaStatus)) {
    return prismaStatus as (typeof valid)[number];
  }
  return 'inactive';
}

function mapRecordPlatform(
  prismaPlatform: string,
): import('@carcommunity/shared/subscription').SubscriptionPlatform {
  const valid = ['apple', 'google', 'manual'] as const;
  if ((valid as readonly string[]).includes(prismaPlatform)) {
    return prismaPlatform as (typeof valid)[number];
  }
  return 'manual';
}

export class SubscriptionService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * Returns the current effective entitlement and safe subscription summary for a user.
   * The most recent active or grace_period record is used to determine entitlement.
   * Raw tokens and sensitive provider data are never included.
   */
  async getSubscriptionForUser(userId: string): Promise<SubscriptionForUserResult> {
    const records = await this.prisma.subscriptionRecord.findMany({
      where: { userId },
      select: {
        platform: true,
        status: true,
        entitlement: true,
        startsAt: true,
        expiresAt: true,
      },
      orderBy: { createdAt: 'desc' },
    });

    const summaries: SubscriptionSourceSummary[] = records.map((r) => ({
      platform: mapRecordPlatform(r.platform),
      status: mapRecordStatus(r.status),
      entitlement: r.entitlement,
      startsAt: r.startsAt ? r.startsAt.toISOString() : null,
      expiresAt: r.expiresAt ? r.expiresAt.toISOString() : null,
    }));

    const effectiveEntitlement = getEffectiveEntitlement(summaries);
    const activeRecord = summaries.find((s) => isSubscriptionActiveStatus(s.status)) ?? null;

    return {
      userId,
      entitlement: effectiveEntitlement,
      subscription: activeRecord,
    };
  }

  /**
   * Returns the admin-facing subscription summary for a specific user.
   * Includes a flag if the user is suspended while holding an active subscription.
   * Raw tokens are never included.
   */
  async getAdminSubscriptionForUser(userId: string): Promise<AdminSubscriptionForUserResult | null> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { id: true, status: true },
    });

    if (!user) {
      return null;
    }

    const result = await this.getSubscriptionForUser(userId);

    const isSuspendedWithActiveSubscription =
      (isSuspendedStatus(user.status) || user.status === 'deleted') &&
      result.subscription !== null &&
      isSubscriptionActiveStatus(result.subscription.status);

    return {
      userId,
      entitlement: result.entitlement,
      subscription: result.subscription,
      isSuspendedWithActiveSubscription,
    };
  }
}

export function createSubscriptionService(prisma: PrismaClient): SubscriptionService {
  return new SubscriptionService(prisma);
}

export function buildAdminUserSubscriptionSummary(
  result: AdminSubscriptionForUserResult,
): AdminUserSubscriptionSummary {
  return {
    userId: result.userId,
    entitlement: result.entitlement,
    subscription: result.subscription,
    isSuspendedWithActiveSubscription: result.isSuspendedWithActiveSubscription,
  };
}
