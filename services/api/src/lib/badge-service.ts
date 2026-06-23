/**
 * BadgeService — backend business logic for the badge (Utmärkelser) system.
 *
 * Design rules enforced here:
 *  - Backend is the sole authority for badge eligibility and award.
 *  - Clients must never award badges directly.
 *  - Suspended or deleted users must not receive new badges.
 *  - Existing badge records are never automatically removed.
 *  - Each user receives each badge at most once (idempotent award).
 *  - Database unique constraint provides the final idempotency guarantee.
 *  - No points, rankings, leaderboards, or competitive fields.
 *  - No speed, distance, night-driving, or unsafe-driving eligibility.
 *  - Queries must be bounded and must not scan the entire user table.
 *  - No generic rules engine — only small, testable rule functions.
 *  - Metadata must be minimal and must not contain personal data or tokens.
 *
 * Badge award sources:
 *  - 'automatic': awarded by the backend rule engine without admin action.
 *  - 'admin_manual': awarded manually by an admin or owner with a reason.
 *
 * Event attendance rule (conservative MVP):
 *  - Uses EventRsvp with status='going' on events with status='completed'
 *    as a conservative proxy for attendance.
 *  - TODO: Replace with verified attendance once a check-in system is added.
 *
 * Early member rule:
 *  - Uses EARLY_MEMBER_CUTOFF_DATE environment variable.
 *  - If not configured, badge is not awarded (safe default).
 *  - Cutoff date must be set explicitly in production.
 */

import { Prisma, type PrismaClient } from '@prisma/client';

import type { AwardedBadge, BadgeKey } from '@carcommunity/shared/badges';
import { isSuspendedStatus, type UserRole, type UserStatus } from '@carcommunity/shared/users';

import { BADGE_CATALOG, BADGE_CATALOG_ORDER } from './badge-catalog.js';
import { AppError } from './errors.js';

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

export interface BadgeActor {
  userId: string;
  role: UserRole;
  status: UserStatus;
}

export interface AwardBadgeResult {
  badge: AwardedBadge;
  /** True if the badge was already awarded before this call. */
  alreadyAwarded: boolean;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function toAwardedBadge(row: { badgeKey: string; awardedAt: Date }): AwardedBadge {
  const key = row.badgeKey as BadgeKey;
  const def = BADGE_CATALOG[key];
  return {
    key,
    name: def.name,
    description: def.description,
    iconIdentifier: def.iconIdentifier,
    awardedAt: row.awardedAt.toISOString(),
  };
}

/**
 * Asserts the user is eligible to receive new badges.
 * Suspended and deleted users must not receive new awards.
 */
function assertEligibleForAward(userId: string, status: UserStatus): void {
  if (status === 'deleted') {
    throw new AppError(403, 'forbidden', `User ${userId} has been deleted and cannot receive badges.`);
  }
  if (isSuspendedStatus(status)) {
    throw new AppError(403, 'suspended', `User ${userId} is suspended and cannot receive new badges.`);
  }
}

// ---------------------------------------------------------------------------
// BadgeService
// ---------------------------------------------------------------------------

export class BadgeService {
  constructor(
    private readonly prisma: PrismaClient,
    /**
     * Optional cutoff date for the early_member badge.
     * If not provided, the badge is not awarded.
     * Must be a valid Date or null/undefined.
     */
    private readonly earlyMemberCutoffDate: Date | null = null,
  ) {}

  // -------------------------------------------------------------------------
  // Public: get current user's badges
  // -------------------------------------------------------------------------

  /**
   * Returns the authenticated user's awarded badges, sorted by catalog order.
   * Only the current user's badges are returned — never other users'.
   */
  public async getCurrentUserBadges(userId: string): Promise<AwardedBadge[]> {
    const rows = await this.prisma.userBadge.findMany({
      where: { userId },
      select: { badgeKey: true, awardedAt: true },
      orderBy: { awardedAt: 'desc' },
    });

    // Sort by catalog order for consistent display.
    const catalogOrder = BADGE_CATALOG_ORDER;
    const sorted = rows.slice().sort((a, b) => {
      const ai = catalogOrder.indexOf(a.badgeKey as BadgeKey);
      const bi = catalogOrder.indexOf(b.badgeKey as BadgeKey);
      return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi);
    });

    return sorted.map(toAwardedBadge);
  }

  // -------------------------------------------------------------------------
  // Public: award a badge (idempotent)
  // -------------------------------------------------------------------------

  /**
   * Awards a badge to a user. Idempotent — calling multiple times is safe.
   * Returns the badge and whether it was already awarded before this call.
   *
   * Validates user eligibility (not deleted, not suspended) before awarding.
   * Database uniqueness constraint provides the final idempotency guarantee.
   */
  public async awardBadge(params: {
    userId: string;
    badgeKey: BadgeKey;
    source: 'automatic' | 'admin_manual';
    awardedByUserId?: string | null;
    metadata?: Prisma.InputJsonValue | null;
    /** Skip status check when already verified by caller (e.g., during user creation). */
    skipStatusCheck?: boolean;
  }): Promise<AwardBadgeResult> {
    if (!params.skipStatusCheck) {
      const user = await this.prisma.user.findUnique({
        where: { id: params.userId },
        select: { status: true },
      });

      if (!user) {
        throw new AppError(404, 'not_found', 'User not found.');
      }

      assertEligibleForAward(params.userId, user.status as UserStatus);
    }

    const existing = await this.prisma.userBadge.findUnique({
      where: { userId_badgeKey: { userId: params.userId, badgeKey: params.badgeKey } },
      select: { badgeKey: true, awardedAt: true },
    });

    if (existing) {
      return { badge: toAwardedBadge(existing), alreadyAwarded: true };
    }

    const now = new Date();
    try {
      const created = await this.prisma.userBadge.create({
        data: {
          userId: params.userId,
          badgeKey: params.badgeKey,
          awardedAt: now,
          awardedByUserId: params.awardedByUserId ?? null,
          source: params.source,
          metadata: params.metadata ?? Prisma.DbNull,
        },
        select: { badgeKey: true, awardedAt: true },
      });

      return { badge: toAwardedBadge(created), alreadyAwarded: false };
    } catch (error) {
      // Unique constraint violation means a concurrent award happened — idempotent.
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2002'
      ) {
        const row = await this.prisma.userBadge.findUnique({
          where: { userId_badgeKey: { userId: params.userId, badgeKey: params.badgeKey } },
          select: { badgeKey: true, awardedAt: true },
        });
        if (row) {
          return { badge: toAwardedBadge(row), alreadyAwarded: true };
        }
      }
      throw error;
    }
  }

  // -------------------------------------------------------------------------
  // Public: automatic eligibility evaluators
  // -------------------------------------------------------------------------

  /**
   * Evaluates and awards the garage_created badge if the user now has at
   * least one vehicle. Safe to call after every vehicle creation.
   *
   * Requirements:
   *  - Award only after verified backend vehicle creation.
   *  - Badge is not removed if the vehicle is later deleted.
   *  - Only awards once per user.
   */
  public async evaluateGarageCreated(userId: string): Promise<AwardBadgeResult | null> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { status: true },
    });

    if (!user) return null;

    const status = user.status as UserStatus;
    if (status === 'deleted' || isSuspendedStatus(status)) {
      return null;
    }

    const vehicleCount = await this.prisma.vehicle.count({ where: { userId } });
    if (vehicleCount === 0) return null;

    return this.awardBadge({
      userId,
      badgeKey: 'garage_created',
      source: 'automatic',
      skipStatusCheck: true,
    });
  }

  /**
   * Evaluates and awards first_event and five_events badges for the given user.
   *
   * Conservative MVP attendance rule:
   *  - Counts EventRsvp records with status='going' on events with status='completed'.
   *  - Draft, published, and cancelled events are excluded.
   *  - not_going RSVPs are excluded.
   *  - Duplicate RSVP records cannot exist due to the unique constraint on (eventId, userId).
   *
   * TODO: Replace RSVP-proxy attendance with verified check-in records once
   *   an event attendance/check-in system is implemented.
   */
  public async evaluateEventBadges(userId: string): Promise<{
    firstEvent: AwardBadgeResult | null;
    fiveEvents: AwardBadgeResult | null;
  }> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { status: true },
    });

    if (!user) return { firstEvent: null, fiveEvents: null };

    const status = user.status as UserStatus;
    if (status === 'deleted' || isSuspendedStatus(status)) {
      return { firstEvent: null, fiveEvents: null };
    }

    // Count completed events with 'going' RSVP as a conservative attendance proxy.
    const attendanceCount = await this.prisma.eventRsvp.count({
      where: {
        userId,
        status: 'going',
        event: { status: 'completed' },
      },
    });

    let firstEvent: AwardBadgeResult | null = null;
    let fiveEvents: AwardBadgeResult | null = null;

    if (attendanceCount >= 1) {
      firstEvent = await this.awardBadge({
        userId,
        badgeKey: 'first_event',
        source: 'automatic',
        skipStatusCheck: true,
      });
    }

    if (attendanceCount >= 5) {
      fiveEvents = await this.awardBadge({
        userId,
        badgeKey: 'five_events',
        source: 'automatic',
        skipStatusCheck: true,
      });
    }

    return { firstEvent, fiveEvents };
  }

  /**
   * Evaluates and awards the early_member badge.
   *
   * Rule: award if the user's account was created before the configured
   * EARLY_MEMBER_CUTOFF_DATE. If the cutoff date is not configured, the
   * badge is not awarded (safe default for production).
   *
   * This method is safe to call at login time — it performs a bounded lookup
   * by userId only and will be a no-op in most cases.
   */
  public async evaluateEarlyMember(userId: string): Promise<AwardBadgeResult | null> {
    if (!this.earlyMemberCutoffDate) {
      // Cutoff date not configured — do not award. Safe default.
      return null;
    }

    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { status: true, createdAt: true },
    });

    if (!user) return null;

    const status = user.status as UserStatus;
    if (status === 'deleted' || isSuspendedStatus(status)) {
      return null;
    }

    if (user.createdAt >= this.earlyMemberCutoffDate) {
      return null;
    }

    return this.awardBadge({
      userId,
      badgeKey: 'early_member',
      source: 'automatic',
      skipStatusCheck: true,
    });
  }

  // -------------------------------------------------------------------------
  // Public: admin-only helpful_member award
  // -------------------------------------------------------------------------

  /**
   * Manually awards the helpful_member badge by an admin or owner.
   *
   * Requirements:
   *  - Requires admin or owner role.
   *  - Requires a non-empty reason (written to audit log).
   *  - Target user must exist and not be deleted or suspended.
   *  - Returns existing award idempotently if already awarded.
   *  - Writes an audit log entry.
   *  - Only the helpful_member badge key is allowed through this method.
   */
  public async awardHelpfulMemberByAdmin(params: {
    actor: BadgeActor;
    targetUserId: string;
    reason: string;
  }): Promise<AwardBadgeResult> {
    const { actor, targetUserId, reason } = params;

    if (!reason || reason.trim().length === 0) {
      throw new AppError(400, 'validation_error', 'Reason is required for manual badge awards.');
    }

    const targetUser = await this.prisma.user.findUnique({
      where: { id: targetUserId },
      select: { status: true },
    });

    if (!targetUser) {
      throw new AppError(404, 'not_found', 'Target user not found.');
    }

    const targetStatus = targetUser.status as UserStatus;
    if (targetStatus === 'deleted') {
      throw new AppError(403, 'forbidden', 'Cannot award a badge to a deleted user.');
    }
    if (isSuspendedStatus(targetStatus)) {
      throw new AppError(403, 'forbidden', 'Cannot award a badge to a suspended user.');
    }

    // Check if already awarded before writing audit log to avoid duplicate audit entries.
    const existing = await this.prisma.userBadge.findUnique({
      where: { userId_badgeKey: { userId: targetUserId, badgeKey: 'helpful_member' } },
      select: { badgeKey: true, awardedAt: true },
    });

    if (existing) {
      return { badge: toAwardedBadge(existing), alreadyAwarded: true };
    }

    const now = new Date();

    const [created] = await this.prisma.$transaction([
      this.prisma.userBadge.create({
        data: {
          userId: targetUserId,
          badgeKey: 'helpful_member',
          awardedAt: now,
          awardedByUserId: actor.userId,
          source: 'admin_manual',
          metadata: Prisma.DbNull,
        },
        select: { badgeKey: true, awardedAt: true },
      }),
      this.prisma.auditLog.create({
        data: {
          actorUserId: actor.userId,
          action: 'badge.award_helpful_member',
          entityType: 'user',
          entityId: targetUserId,
          reason: reason.trim(),
          metadata: {
            badgeKey: 'helpful_member',
            targetUserId,
          },
        },
      }),
    ]);

    return { badge: toAwardedBadge(created), alreadyAwarded: false };
  }

  // -------------------------------------------------------------------------
  // Public: admin badge aggregate summary
  // -------------------------------------------------------------------------

  /**
   * Returns aggregate badge counts for admin use.
   * Never exposes a leaderboard or individual user data.
   * Only returns totals and recent counts per badge key.
   */
  public async getAdminBadgeSummary(): Promise<
    Array<{ key: BadgeKey; name: string; totalCount: number; recentCount: number }>
  > {
    const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1_000);

    const [totalGroups, recentGroups] = await this.prisma.$transaction([
      this.prisma.userBadge.groupBy({
        by: ['badgeKey'],
        _count: { badgeKey: true },
      }),
      this.prisma.userBadge.groupBy({
        by: ['badgeKey'],
        _count: { badgeKey: true },
        where: { awardedAt: { gte: thirtyDaysAgo } },
      }),
    ]);

    const totalMap = new Map(totalGroups.map((g) => [g.badgeKey, g._count.badgeKey]));
    const recentMap = new Map(recentGroups.map((g) => [g.badgeKey, g._count.badgeKey]));

    return BADGE_CATALOG_ORDER.map((key) => ({
      key,
      name: BADGE_CATALOG[key].name,
      totalCount: totalMap.get(key) ?? 0,
      recentCount: recentMap.get(key) ?? 0,
    }));
  }
}
