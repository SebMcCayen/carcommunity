/**
 * CrownHuntService — backend business logic for the Kronjakt feature.
 *
 * Design rules enforced here:
 *  - Backend is the sole authority for eligibility, claims, and Kronpoäng awards.
 *  - Mobile clients must never calculate or award Kronpoäng.
 *  - Claims are never automatic — the user must explicitly press the collect button.
 *  - Points awarded only when ALL validation steps pass AND risk is acceptable.
 *  - Award and claim creation happen in a single database transaction.
 *  - Idempotency keys prevent duplicate awards for the same button press.
 *  - Risk-review claims store no points and flag the claim for admin review.
 *  - No route history is created. Exact claim coordinates are not stored.
 *  - Suspended and deleted users cannot claim.
 *  - The `crownHunt` feature flag must be enabled.
 *  - Only users with `member_monthly` entitlement may claim.
 *  - Daily claim limits and repeat rules are enforced.
 *  - Anti-fraud signals are evaluated before awarding.
 *
 * TODO: Add Google Play Integrity signal once available.
 * TODO: Add Apple App Attest / DeviceCheck signal once available.
 * TODO: Add admin review workflow for risk_review claims.
 * TODO: Add richer anomaly detection.
 */

import { createHash } from 'node:crypto';

import { Prisma, type PrismaClient } from '@prisma/client';
import type { CrownHuntClaimResult, CrownHuntRepeatRule } from '@carcommunity/shared/crown-hunt';
import {
  MAX_CLAIM_SPEED_MPS,
  MIN_GEOFENCE_RADIUS_METERS,
  MAX_GEOFENCE_RADIUS_METERS,
  MIN_REWARD_POINTS,
  MAX_REWARD_POINTS,
  MAX_TITLE_LENGTH,
  MAX_DESCRIPTION_LENGTH,
  DEFAULT_CROWN_HUNT_PAGE_SIZE,
  MAX_CROWN_HUNT_PAGE_SIZE,
  CROWN_HUNT_REPEAT_RULES,
} from '@carcommunity/shared/crown-hunt';
import type {
  CrownHuntPointSummary,
  CrownHuntPointDetail,
  CrownHuntClaimHistoryEntry,
  AdminCrownHuntPointSummary,
  AdminCrownHuntClaimSummary,
  AdminCreateCrownHuntPointRequest,
  AdminUpdateCrownHuntPointRequest,
  AdminActivateCrownHuntPointRequest,
} from '@carcommunity/shared/crown-hunt';
import { canAccessAdminFeatures, canAccessMemberFeatures, isSuspendedStatus } from '@carcommunity/shared/users';
import type { UserRole, UserStatus, SubscriptionEntitlement } from '@carcommunity/shared/users';

import { AppError } from './errors.js';
import { PointsService } from './points-service.js';
import type { WriteAuditLogInput } from './moderation-service.js';
import {
  haversineDistanceMeters,
  isValidCoordinate,
  isPositionFresh,
  isSpeedSafe,
  isWithinGeofence,
  isPlausibleJump,
} from './crown-hunt-geo.js';
import {
  evaluateClaimRisk,
  HIGH_VELOCITY_WINDOW_SECONDS,
} from './crown-hunt-risk.js';

// ---------------------------------------------------------------------------
// Limits (configurable constants — never hardcode unlimited behaviour)
// ---------------------------------------------------------------------------

/** Maximum successful claims a user may make per calendar day. */
export const MAX_DAILY_SUCCESSFUL_CLAIMS = 10;
/** Maximum claim attempts (any result) per minute per user. */
export const MAX_CLAIM_ATTEMPTS_PER_MINUTE = 3;
/** Minimum seconds between two consecutive successful claims for the same user. */
export const MIN_SECONDS_BETWEEN_SUCCESSFUL_CLAIMS = 30;

// ---------------------------------------------------------------------------
// Interfaces
// ---------------------------------------------------------------------------

export interface ClaimActor {
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface ClaimInput {
  actor: ClaimActor;
  pointId: string;
  latitude: number;
  longitude: number;
  accuracyMeters?: number | null;
  speedMetersPerSecond?: number | null;
  recordedAt: string;
  idempotencyKey: string;
  /** Platform integrity placeholder. null until native integration is added. */
  platformIntegrityPassed?: boolean | null;
  crownHuntFeatureEnabled: boolean;
}

export interface ClaimOutput {
  result: CrownHuntClaimResult;
  pointsAwarded: number | null;
  newBalance: number | null;
  message: string;
}

export interface ListPointsInput {
  userId: string;
  page?: number;
  pageSize?: number;
  /** Optional viewport bounding box for geographic filtering. */
  minLat?: number;
  maxLat?: number;
  minLon?: number;
  maxLon?: number;
}

export interface ListPointsOutput {
  points: CrownHuntPointSummary[];
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}

export interface ListClaimHistoryInput {
  userId: string;
  page?: number;
  pageSize?: number;
}

export interface ListClaimHistoryOutput {
  claims: CrownHuntClaimHistoryEntry[];
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}

export interface AdminListPointsInput {
  page?: number;
  pageSize?: number;
  status?: string;
}

export interface AdminListPointsOutput {
  points: AdminCrownHuntPointSummary[];
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}

export interface AdminListClaimsInput {
  page?: number;
  pageSize?: number;
  result?: string;
}

export interface AdminListClaimsOutput {
  claims: AdminCrownHuntClaimSummary[];
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
}

// ---------------------------------------------------------------------------
// Swedish claim result messages
// ---------------------------------------------------------------------------

function scopeClaimIdempotencyKey(userId: string, idempotencyKey: string): string {
  return createHash('sha256')
    .update(userId)
    .update(':')
    .update(idempotencyKey)
    .digest('hex');
}

function isUniqueConstraintError(error: unknown): error is { code: string } {
  return typeof error === 'object' && error !== null && 'code' in error && error.code === 'P2002';
}

function getClaimMessage(result: CrownHuntClaimResult): string {
  switch (result) {
    case 'awarded':
      return 'Belöningen har lagts till i ditt Kronpoäng-saldo.';
    case 'already_claimed':
      return 'Du har redan samlat in den här belöningen.';
    case 'outside_geofence':
      return 'Du är för långt från platsen.';
    case 'moving_too_fast':
      return 'Du rör dig för snabbt för att samla in. Stanna säkert innan du samlar in belöningen.';
    case 'position_too_old':
      return 'Din position är för gammal. Vänta en stund och försök igen.';
    case 'point_inactive':
      return 'Den här belöningspunkten är inte längre tillgänglig.';
    case 'cooldown_active':
      return 'Du behöver vänta lite innan du kan samla in igen.';
    case 'daily_limit_reached':
      return 'Du har nått dagens gräns för Kronjakt. Försök igen imorgon.';
    case 'risk_review':
      return 'Claimen behöver granskas och inga poäng har delats ut ännu.';
    case 'feature_disabled':
      return 'Kronjakt är för tillfället inte tillgängligt.';
    case 'not_eligible':
      return 'Du behöver ett aktivt Kronjakt-medlemskap för att delta.';
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function isPointCurrentlyAvailable(point: {
  availableFrom: Date | null;
  availableUntil: Date | null;
}, now: Date): boolean {
  if (point.availableFrom && now < point.availableFrom) return false;
  if (point.availableUntil && now > point.availableUntil) return false;
  return true;
}

/** Returns start of current UTC calendar day. */
function startOfUtcDay(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

/** Returns start of current UTC ISO week (Monday). */
function startOfUtcWeek(date: Date): Date {
  const d = new Date(date);
  const day = d.getUTCDay();
  const diff = (day === 0 ? -6 : 1 - day);
  d.setUTCDate(d.getUTCDate() + diff);
  d.setUTCHours(0, 0, 0, 0);
  return d;
}

async function writeAuditLog(
  prisma: PrismaClient,
  entry: WriteAuditLogInput,
): Promise<void> {
  await prisma.auditLog.create({
    data: {
      actorUserId: entry.actorUserId,
      action: entry.action,
      entityType: entry.entityType,
      entityId: entry.entityId ?? null,
      reason: entry.reason ?? null,
      metadata: entry.metadata ?? Prisma.JsonNull,
    },
  });
}

// ---------------------------------------------------------------------------
// CrownHuntService
// ---------------------------------------------------------------------------

export class CrownHuntService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly pointsService: PointsService,
  ) {}

  // ---------------------------------------------------------------------------
  // Mobile: list active points
  // ---------------------------------------------------------------------------

  public async listActivePoints(input: ListPointsInput): Promise<ListPointsOutput> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_CROWN_HUNT_PAGE_SIZE));
    const skip = (page - 1) * pageSize;
    const now = new Date();

    const where: Prisma.CrownHuntPointWhereInput = {
      status: 'active',
      AND: [
        { OR: [{ availableFrom: null }, { availableFrom: { lte: now } }] },
        { OR: [{ availableUntil: null }, { availableUntil: { gte: now } }] },
      ],
    };

    // Optional viewport geographic filter
    if (
      input.minLat !== undefined &&
      input.maxLat !== undefined &&
      input.minLon !== undefined &&
      input.maxLon !== undefined
    ) {
      where.latitude = { gte: input.minLat, lte: input.maxLat };
      where.longitude = { gte: input.minLon, lte: input.maxLon };
    }

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.crownHuntPoint.count({ where }),
      this.prisma.crownHuntPoint.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
      }),
    ]);

    // Fetch the current user's successful claims for these points
    const pointIds = rows.map((r) => r.id);
    const userClaims = pointIds.length > 0
      ? await this.prisma.crownHuntClaim.findMany({
          where: {
            userId: input.userId,
            pointId: { in: pointIds },
            result: 'awarded',
          },
          select: { pointId: true },
        })
      : [];

    const claimedPointIds = new Set(userClaims.map((c) => c.pointId));

    const points: CrownHuntPointSummary[] = rows.map((row) => ({
      pointId: row.id,
      title: row.title,
      description: row.description,
      latitude: row.latitude,
      longitude: row.longitude,
      geofenceRadiusMeters: row.geofenceRadiusMeters,
      rewardPoints: row.rewardPoints,
      status: row.status as CrownHuntPointSummary['status'],
      availableFrom: row.availableFrom?.toISOString() ?? null,
      availableUntil: row.availableUntil?.toISOString() ?? null,
      claimedByCurrentUser: claimedPointIds.has(row.id),
      repeatRule: row.repeatRule as CrownHuntRepeatRule,
    }));

    return { points, page, pageSize, total, hasNext: skip + rows.length < total };
  }

  // ---------------------------------------------------------------------------
  // Mobile: get point detail
  // ---------------------------------------------------------------------------

  public async getPointDetail(pointId: string, userId: string): Promise<CrownHuntPointDetail> {
    const now = new Date();

    const point = await this.prisma.crownHuntPoint.findUnique({
      where: { id: pointId },
    });

    if (!point || point.status !== 'active' || !isPointCurrentlyAvailable(point, now)) {
      throw new AppError(404, 'not_found', 'Kronjakt point not found or not available.');
    }

    const claimed = await this.prisma.crownHuntClaim.findFirst({
      where: { pointId, userId, result: 'awarded' },
      select: { id: true },
    });

    return {
      pointId: point.id,
      title: point.title,
      description: point.description,
      latitude: point.latitude,
      longitude: point.longitude,
      geofenceRadiusMeters: point.geofenceRadiusMeters,
      rewardPoints: point.rewardPoints,
      status: point.status as CrownHuntPointDetail['status'],
      availableFrom: point.availableFrom?.toISOString() ?? null,
      availableUntil: point.availableUntil?.toISOString() ?? null,
      claimedByCurrentUser: claimed !== null,
      repeatRule: point.repeatRule as CrownHuntRepeatRule,
      safetyInstruction: 'Stanna säkert innan du samlar in belöningen.',
    };
  }

  // ---------------------------------------------------------------------------
  // Mobile: claim a point
  // ---------------------------------------------------------------------------

  public async claimPoint(input: ClaimInput): Promise<ClaimOutput> {
    const { actor, pointId, idempotencyKey } = input;
    const now = new Date();
    const scopedIdempotencyKey = scopeClaimIdempotencyKey(actor.userId, idempotencyKey);

    // 1. Feature flag check
    if (!input.crownHuntFeatureEnabled) {
      return { result: 'feature_disabled', pointsAwarded: null, newBalance: null, message: getClaimMessage('feature_disabled') };
    }

    // 2. Account status check
    if (actor.status === 'deleted') {
      return { result: 'not_eligible', pointsAwarded: null, newBalance: null, message: getClaimMessage('not_eligible') };
    }
    if (isSuspendedStatus(actor.status)) {
      return { result: 'not_eligible', pointsAwarded: null, newBalance: null, message: getClaimMessage('not_eligible') };
    }

    // 3. Entitlement check
    if (!canAccessMemberFeatures(actor)) {
      return { result: 'not_eligible', pointsAwarded: null, newBalance: null, message: getClaimMessage('not_eligible') };
    }

    // 4. Check idempotency key (duplicate submission guard)
    const existingClaim = await this.prisma.crownHuntClaim.findUnique({
      where: { idempotencyKey: scopedIdempotencyKey },
      select: { result: true, pointId: true, pointsLedgerEntryId: true },
    });

    if (existingClaim) {
      if (existingClaim.pointId !== pointId) {
        // Idempotency key reuse across different points — treat as duplicate
        return { result: 'already_claimed', pointsAwarded: null, newBalance: null, message: getClaimMessage('already_claimed') };
      }
      return this.buildReplayClaimOutput(existingClaim);
    }

    // 5. Load the point
    const point = await this.prisma.crownHuntPoint.findUnique({
      where: { id: pointId },
    });

    if (!point || point.status !== 'active' || !isPointCurrentlyAvailable(point, now)) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: 'point_inactive',
          claimedAt: now,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: 'point_inactive', pointsAwarded: null, newBalance: null, message: getClaimMessage('point_inactive') };
    }

    // 6. Validate coordinate inputs
    if (!isValidCoordinate(input.latitude, input.longitude)) {
      throw new AppError(400, 'validation_error', 'Invalid coordinates provided.');
    }

    const recordedAtDate = new Date(input.recordedAt);
    if (isNaN(recordedAtDate.getTime())) {
      throw new AppError(400, 'validation_error', 'Invalid recordedAt timestamp.');
    }

    // 7. Position freshness
    const positionStale = !isPositionFresh(input.recordedAt, now.getTime());
    if (positionStale) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: 'position_too_old',
          claimedAt: now,
          positionRecordedAt: recordedAtDate,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: 'position_too_old', pointsAwarded: null, newBalance: null, message: getClaimMessage('position_too_old') };
    }

    // 8. Server-side distance calculation
    const distanceMeters = haversineDistanceMeters(
      input.latitude, input.longitude,
      point.latitude, point.longitude,
    );

    // 9. Geofence check
    if (!isWithinGeofence(distanceMeters, point.geofenceRadiusMeters, input.accuracyMeters)) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: 'outside_geofence',
          claimedAt: now,
          distanceMeters,
          positionRecordedAt: recordedAtDate,
          reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: 'outside_geofence', pointsAwarded: null, newBalance: null, message: getClaimMessage('outside_geofence') };
    }

    // 10. Speed check
    if (!isSpeedSafe(input.speedMetersPerSecond, MAX_CLAIM_SPEED_MPS)) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: 'moving_too_fast',
          claimedAt: now,
          distanceMeters,
          positionRecordedAt: recordedAtDate,
          reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: 'moving_too_fast', pointsAwarded: null, newBalance: null, message: getClaimMessage('moving_too_fast') };
    }

    // 11. Repeat and cooldown rules
    const repeatResult = await this.checkRepeatRule(actor.userId, pointId, point.repeatRule as CrownHuntRepeatRule, now);
    if (repeatResult !== null) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: repeatResult,
          claimedAt: now,
          distanceMeters,
          positionRecordedAt: recordedAtDate,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: repeatResult, pointsAwarded: null, newBalance: null, message: getClaimMessage(repeatResult) };
    }

    // 12. Daily limit check
    const dailyLimitReached = await this.checkDailyLimit(actor.userId, now);
    if (dailyLimitReached) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: 'daily_limit_reached',
          claimedAt: now,
          distanceMeters,
          positionRecordedAt: recordedAtDate,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: 'daily_limit_reached', pointsAwarded: null, newBalance: null, message: getClaimMessage('daily_limit_reached') };
    }

    // 13. Rate limit (attempt count per minute)
    const attemptsInLastMinute = await this.countRecentAttempts(actor.userId, 60, now);
    const successesInVelocityWindow = await this.countRecentSuccesses(
      actor.userId,
      HIGH_VELOCITY_WINDOW_SECONDS,
      now,
    );

    // Fetch latest trusted position for jump detection
    const latestPosition = await this.prisma.liveLocationLatestPosition.findFirst({
      where: { userId: actor.userId },
      orderBy: { recordedAt: 'desc' },
      select: { latitude: true, longitude: true, recordedAt: true },
    });

    const impossibleJump = latestPosition
      ? !isPlausibleJump(
          latestPosition.latitude,
          latestPosition.longitude,
          latestPosition.recordedAt.toISOString(),
          input.latitude,
          input.longitude,
          now.getTime(),
        )
      : false;

    // 14. Risk evaluation
    const riskEval = evaluateClaimRisk({
      positionStale,
      poorAccuracy: (input.accuracyMeters ?? 0) > 50,
      impossibleJump,
      duplicateIdempotencyKey: false, // already handled above
      attemptsInLastMinute,
      successfulClaimsInVelocityWindow: successesInVelocityWindow,
      geofenceEdgeAttempts: 0, // TODO: implement geofence-edge counting
      accuracyMeters: input.accuracyMeters ?? null,
      platformIntegrityPassed: input.platformIntegrityPassed ?? null,
    });

    if (riskEval.isHighRisk) {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          result: 'risk_review',
          claimedAt: now,
          distanceMeters,
          positionRecordedAt: recordedAtDate,
          reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
          riskScore: riskEval.riskScore,
          riskReasons: riskEval.riskReasons as Prisma.InputJsonValue,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
      return { result: 'risk_review', pointsAwarded: null, newBalance: null, message: getClaimMessage('risk_review') };
    }

    // 15. Award points then create claim record
    // creditPoints uses its own internal transaction for the ledger entry.
    // The claim record is created immediately after using the same idempotency key
    // to link them. Concurrent duplicates are prevented by the unique constraint
    // on both idempotencyKey columns.
    const rewardPoints = point.rewardPoints;
    const description = `Kronjakt: ${point.title}`;
    const ledgerIdempotencyKey = `crown_hunt_claim:${scopedIdempotencyKey}`;

    let pointsAwarded: number | null = null;
    let newBalance: number | null = null;

    const ledgerEntry = await this.pointsService.creditPoints({
      userId: actor.userId,
      amount: rewardPoints,
      transactionType: 'earn',
      source: 'crown_hunt',
      description,
      idempotencyKey: ledgerIdempotencyKey,
      relatedEntityType: 'crown_hunt_point',
      relatedEntityId: pointId,
      createdByUserId: null,
      metadata: null,
    });

    // Create the claim record (unique idempotencyKey prevents duplicate on concurrent retry)
    try {
      await this.prisma.crownHuntClaim.create({
        data: {
          pointId,
          userId: actor.userId,
          pointsLedgerEntryId: ledgerEntry.transactionId,
          result: 'awarded',
          claimedAt: now,
          distanceMeters,
          positionRecordedAt: recordedAtDate,
          reportedSpeedMetersPerSecond: input.speedMetersPerSecond ?? null,
          riskScore: riskEval.riskScore,
          riskReasons: riskEval.riskReasons.length > 0 ? riskEval.riskReasons as Prisma.InputJsonValue : Prisma.JsonNull,
          idempotencyKey: scopedIdempotencyKey,
        },
      });
    } catch (error) {
      if (isUniqueConstraintError(error)) {
        const replayClaim = await this.prisma.crownHuntClaim.findUnique({
          where: { idempotencyKey: scopedIdempotencyKey },
          select: { result: true, pointId: true, pointsLedgerEntryId: true },
        });
        if (replayClaim) {
          return this.buildReplayClaimOutput(replayClaim);
        }
      }
      throw error;
    }

    pointsAwarded = ledgerEntry.amount;
    newBalance = ledgerEntry.balanceAfter;

    return { result: 'awarded', pointsAwarded, newBalance, message: getClaimMessage('awarded') };
  }

  // ---------------------------------------------------------------------------
  // Mobile: list user's claim history
  // ---------------------------------------------------------------------------

  public async listClaimHistory(input: ListClaimHistoryInput): Promise<ListClaimHistoryOutput> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_CROWN_HUNT_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const where: Prisma.CrownHuntClaimWhereInput = { userId: input.userId };

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.crownHuntClaim.count({ where }),
      this.prisma.crownHuntClaim.findMany({
        where,
        include: { point: { select: { title: true, rewardPoints: true } } },
        orderBy: { claimedAt: 'desc' },
        skip,
        take: pageSize,
      }),
    ]);

    const claims: CrownHuntClaimHistoryEntry[] = rows.map((row) => ({
      claimId: row.id,
      pointId: row.pointId,
      pointTitle: row.point.title,
      result: row.result as CrownHuntClaimResult,
      pointsAwarded: row.result === 'awarded' ? row.point.rewardPoints : 0,
      claimedAt: row.claimedAt.toISOString(),
    }));

    return { claims, page, pageSize, total, hasNext: skip + rows.length < total };
  }

  private async buildReplayClaimOutput(existingClaim: {
    result: string;
    pointId: string;
    pointsLedgerEntryId: string | null;
  }): Promise<ClaimOutput> {
    const result = existingClaim.result as CrownHuntClaimResult;
    let pointsAwarded: number | null = null;
    let newBalance: number | null = null;

    if (result === 'awarded' && existingClaim.pointsLedgerEntryId) {
      const ledgerEntry = await this.prisma.pointsLedgerEntry.findUnique({
        where: { id: existingClaim.pointsLedgerEntryId },
        select: { amount: true, balanceAfter: true },
      });
      pointsAwarded = ledgerEntry?.amount ?? null;
      newBalance = ledgerEntry?.balanceAfter ?? null;
    }

    return { result, pointsAwarded, newBalance, message: getClaimMessage(result) };
  }

  // ---------------------------------------------------------------------------
  // Admin: list all points
  // ---------------------------------------------------------------------------

  public async adminListPoints(input: AdminListPointsInput): Promise<AdminListPointsOutput> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_CROWN_HUNT_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const where: Prisma.CrownHuntPointWhereInput = {};
    if (input.status) {
      where.status = input.status as Prisma.EnumCrownHuntPointStatusFilter;
    }

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.crownHuntPoint.count({ where }),
      this.prisma.crownHuntPoint.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
      }),
    ]);

    // Count successful claims per point
    const pointIds = rows.map((r) => r.id);
    const claimCounts = pointIds.length > 0
      ? await this.prisma.crownHuntClaim.groupBy({
          by: ['pointId'],
          where: { pointId: { in: pointIds }, result: 'awarded' },
          _count: { id: true },
        })
      : [];

    const countByPointId = new Map(claimCounts.map((c) => [c.pointId, c._count.id]));

    const points: AdminCrownHuntPointSummary[] = rows.map((row) => ({
      pointId: row.id,
      title: row.title,
      description: row.description,
      latitude: row.latitude,
      longitude: row.longitude,
      geofenceRadiusMeters: row.geofenceRadiusMeters,
      rewardPoints: row.rewardPoints,
      status: row.status as AdminCrownHuntPointSummary['status'],
      repeatRule: row.repeatRule as CrownHuntRepeatRule,
      availableFrom: row.availableFrom?.toISOString() ?? null,
      availableUntil: row.availableUntil?.toISOString() ?? null,
      approvedAt: row.approvedAt?.toISOString() ?? null,
      approvedByUserId: row.approvedByUserId,
      createdByUserId: row.createdByUserId,
      createdAt: row.createdAt.toISOString(),
      updatedAt: row.updatedAt.toISOString(),
      totalClaims: countByPointId.get(row.id) ?? 0,
    }));

    return { points, page, pageSize, total, hasNext: skip + rows.length < total };
  }

  // ---------------------------------------------------------------------------
  // Admin: create point
  // ---------------------------------------------------------------------------

  public async adminCreatePoint(
    actorUserId: string,
    data: AdminCreateCrownHuntPointRequest,
  ): Promise<AdminCrownHuntPointSummary> {
    validatePointFields(data);

    const point = await this.prisma.crownHuntPoint.create({
      data: {
        title: data.title.trim(),
        description: data.description?.trim() ?? null,
        latitude: data.latitude,
        longitude: data.longitude,
        geofenceRadiusMeters: data.geofenceRadiusMeters,
        rewardPoints: data.rewardPoints,
        repeatRule: data.repeatRule,
        availableFrom: data.availableFrom ? new Date(data.availableFrom) : null,
        availableUntil: data.availableUntil ? new Date(data.availableUntil) : null,
        createdByUserId: actorUserId,
        // status defaults to 'draft'
      },
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'crown_hunt_point.created',
      entityType: 'CrownHuntPoint',
      entityId: point.id,
      reason: null,
      metadata: { title: point.title, status: point.status },
    });

    return toAdminPointSummary(point, 0);
  }

  // ---------------------------------------------------------------------------
  // Admin: update point
  // ---------------------------------------------------------------------------

  public async adminUpdatePoint(
    actorUserId: string,
    pointId: string,
    data: AdminUpdateCrownHuntPointRequest,
  ): Promise<AdminCrownHuntPointSummary> {
    const existing = await this.prisma.crownHuntPoint.findUnique({ where: { id: pointId } });
    if (!existing) {
      throw new AppError(404, 'not_found', 'Kronjakt point not found.');
    }
    if (existing.status !== 'draft' && existing.status !== 'paused') {
      throw new AppError(400, 'validation_error', 'Only draft or paused points may be edited.');
    }

    const merged = {
      title: data.title ?? existing.title,
      description: data.description !== undefined ? data.description : existing.description,
      latitude: data.latitude ?? existing.latitude,
      longitude: data.longitude ?? existing.longitude,
      geofenceRadiusMeters: data.geofenceRadiusMeters ?? existing.geofenceRadiusMeters,
      rewardPoints: data.rewardPoints ?? existing.rewardPoints,
      repeatRule: (data.repeatRule ?? existing.repeatRule) as CrownHuntRepeatRule,
      availableFrom: data.availableFrom !== undefined
        ? (data.availableFrom ? data.availableFrom : null)
        : existing.availableFrom?.toISOString() ?? null,
      availableUntil: data.availableUntil !== undefined
        ? (data.availableUntil ? data.availableUntil : null)
        : existing.availableUntil?.toISOString() ?? null,
    };
    validatePointFields(merged);

    const updated = await this.prisma.crownHuntPoint.update({
      where: { id: pointId },
      data: {
        title: merged.title.trim(),
        description: merged.description?.trim() ?? null,
        latitude: merged.latitude,
        longitude: merged.longitude,
        geofenceRadiusMeters: merged.geofenceRadiusMeters,
        rewardPoints: merged.rewardPoints,
        repeatRule: merged.repeatRule,
        availableFrom: merged.availableFrom ? new Date(merged.availableFrom) : null,
        availableUntil: merged.availableUntil ? new Date(merged.availableUntil) : null,
      },
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'crown_hunt_point.updated',
      entityType: 'CrownHuntPoint',
      entityId: pointId,
      reason: null,
      metadata: { title: updated.title },
    });

    const claimCount = await this.prisma.crownHuntClaim.count({
      where: { pointId, result: 'awarded' },
    });
    return toAdminPointSummary(updated, claimCount);
  }

  // ---------------------------------------------------------------------------
  // Admin: activate point
  // ---------------------------------------------------------------------------

  public async adminActivatePoint(
    actorUserId: string,
    actorRole: UserRole,
    pointId: string,
    data: AdminActivateCrownHuntPointRequest,
  ): Promise<AdminCrownHuntPointSummary> {
    if (!canAccessAdminFeatures({ role: actorRole, status: 'active' as UserStatus })) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }
    if (!data.safeLocationConfirmed) {
      throw new AppError(400, 'validation_error', 'Safety confirmation is required to activate a point.');
    }
    if (!data.approvalNote || data.approvalNote.trim().length < 3) {
      throw new AppError(400, 'validation_error', 'A safety approval note is required.');
    }

    const existing = await this.prisma.crownHuntPoint.findUnique({ where: { id: pointId } });
    if (!existing) {
      throw new AppError(404, 'not_found', 'Kronjakt point not found.');
    }
    if (existing.status === 'ended') {
      throw new AppError(400, 'validation_error', 'Ended points cannot be activated.');
    }

    const updated = await this.prisma.crownHuntPoint.update({
      where: { id: pointId },
      data: {
        status: 'active',
        approvedAt: new Date(),
        approvedByUserId: actorUserId,
      },
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'crown_hunt_point.activated',
      entityType: 'CrownHuntPoint',
      entityId: pointId,
      reason: data.approvalNote.trim(),
      metadata: { title: updated.title, safeLocationConfirmed: true },
    });

    const claimCount = await this.prisma.crownHuntClaim.count({
      where: { pointId, result: 'awarded' },
    });
    return toAdminPointSummary(updated, claimCount);
  }

  // ---------------------------------------------------------------------------
  // Admin: pause point
  // ---------------------------------------------------------------------------

  public async adminPausePoint(
    actorUserId: string,
    actorRole: UserRole,
    pointId: string,
    reason: string,
  ): Promise<AdminCrownHuntPointSummary> {
    if (!canAccessAdminFeatures({ role: actorRole, status: 'active' as UserStatus })) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }

    const existing = await this.prisma.crownHuntPoint.findUnique({ where: { id: pointId } });
    if (!existing) {
      throw new AppError(404, 'not_found', 'Kronjakt point not found.');
    }
    if (existing.status === 'ended') {
      throw new AppError(400, 'validation_error', 'Ended points cannot be paused.');
    }

    const updated = await this.prisma.crownHuntPoint.update({
      where: { id: pointId },
      data: { status: 'paused' },
    });

    await writeAuditLog(this.prisma, {
      actorUserId,
      action: 'crown_hunt_point.paused',
      entityType: 'CrownHuntPoint',
      entityId: pointId,
      reason: reason?.trim() || null,
      metadata: { title: updated.title },
    });

    const claimCount = await this.prisma.crownHuntClaim.count({
      where: { pointId, result: 'awarded' },
    });
    return toAdminPointSummary(updated, claimCount);
  }

  // ---------------------------------------------------------------------------
  // Admin: list claims (for risk review)
  // ---------------------------------------------------------------------------

  public async adminListClaims(input: AdminListClaimsInput): Promise<AdminListClaimsOutput> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(MAX_CROWN_HUNT_PAGE_SIZE, Math.max(1, input.pageSize ?? DEFAULT_CROWN_HUNT_PAGE_SIZE));
    const skip = (page - 1) * pageSize;

    const where: Prisma.CrownHuntClaimWhereInput = {};
    if (input.result) {
      where.result = input.result as Prisma.EnumCrownHuntClaimResultFilter;
    }

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.crownHuntClaim.count({ where }),
      this.prisma.crownHuntClaim.findMany({
        where,
        include: { point: { select: { title: true, rewardPoints: true } } },
        orderBy: { claimedAt: 'desc' },
        skip,
        take: pageSize,
      }),
    ]);

    const claims: AdminCrownHuntClaimSummary[] = rows.map((row) => ({
      claimId: row.id,
      pointId: row.pointId,
      pointTitle: row.point.title,
      userId: row.userId,
      result: row.result as CrownHuntClaimResult,
      distanceMeters: row.distanceMeters,
      // Return safe category labels only — never raw score or threshold values
      riskReasonCategories: Array.isArray(row.riskReasons) ? row.riskReasons as string[] : [],
      claimedAt: row.claimedAt.toISOString(),
    }));

    return { claims, page, pageSize, total, hasNext: skip + rows.length < total };
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private async checkRepeatRule(
    userId: string,
    pointId: string,
    repeatRule: CrownHuntRepeatRule,
    now: Date,
  ): Promise<CrownHuntClaimResult | null> {
    if (repeatRule === 'once') {
      const prior = await this.prisma.crownHuntClaim.findFirst({
        where: { userId, pointId, result: 'awarded' },
        select: { id: true },
      });
      return prior ? 'already_claimed' : null;
    }

    if (repeatRule === 'daily') {
      const windowStart = startOfUtcDay(now);
      const prior = await this.prisma.crownHuntClaim.findFirst({
        where: {
          userId, pointId, result: 'awarded',
          claimedAt: { gte: windowStart },
        },
        select: { id: true },
      });
      return prior ? 'already_claimed' : null;
    }

    if (repeatRule === 'weekly') {
      const windowStart = startOfUtcWeek(now);
      const prior = await this.prisma.crownHuntClaim.findFirst({
        where: {
          userId, pointId, result: 'awarded',
          claimedAt: { gte: windowStart },
        },
        select: { id: true },
      });
      return prior ? 'already_claimed' : null;
    }

    return null;
  }

  private async checkDailyLimit(userId: string, now: Date): Promise<boolean> {
    const dayStart = startOfUtcDay(now);
    const count = await this.prisma.crownHuntClaim.count({
      where: { userId, result: 'awarded', claimedAt: { gte: dayStart } },
    });
    return count >= MAX_DAILY_SUCCESSFUL_CLAIMS;
  }

  private async countRecentAttempts(userId: string, windowSeconds: number, now: Date): Promise<number> {
    const windowStart = new Date(now.getTime() - windowSeconds * 1000);
    return this.prisma.crownHuntClaim.count({
      where: { userId, createdAt: { gte: windowStart } },
    });
  }

  private async countRecentSuccesses(userId: string, windowSeconds: number, now: Date): Promise<number> {
    const windowStart = new Date(now.getTime() - windowSeconds * 1000);
    return this.prisma.crownHuntClaim.count({
      where: { userId, result: 'awarded', claimedAt: { gte: windowStart } },
    });
  }
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

function toAdminPointSummary(
  row: {
    id: string;
    title: string;
    description: string | null;
    latitude: number;
    longitude: number;
    geofenceRadiusMeters: number;
    rewardPoints: number;
    status: string;
    repeatRule: string;
    availableFrom: Date | null;
    availableUntil: Date | null;
    approvedAt: Date | null;
    approvedByUserId: string | null;
    createdByUserId: string;
    createdAt: Date;
    updatedAt: Date;
  },
  totalClaims: number,
): AdminCrownHuntPointSummary {
  return {
    pointId: row.id,
    title: row.title,
    description: row.description,
    latitude: row.latitude,
    longitude: row.longitude,
    geofenceRadiusMeters: row.geofenceRadiusMeters,
    rewardPoints: row.rewardPoints,
    status: row.status as AdminCrownHuntPointSummary['status'],
    repeatRule: row.repeatRule as CrownHuntRepeatRule,
    availableFrom: row.availableFrom?.toISOString() ?? null,
    availableUntil: row.availableUntil?.toISOString() ?? null,
    approvedAt: row.approvedAt?.toISOString() ?? null,
    approvedByUserId: row.approvedByUserId,
    createdByUserId: row.createdByUserId,
    createdAt: row.createdAt.toISOString(),
    updatedAt: row.updatedAt.toISOString(),
    totalClaims,
  };
}

// ---------------------------------------------------------------------------
// Point field validation (centralized)
// ---------------------------------------------------------------------------

interface PointFieldsToValidate {
  title: string;
  description?: string | null;
  latitude: number;
  longitude: number;
  geofenceRadiusMeters: number;
  rewardPoints: number;
  repeatRule: string;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

function validatePointFields(data: PointFieldsToValidate): void {
  if (!data.title || data.title.trim().length === 0) {
    throw new AppError(400, 'validation_error', 'Title is required.');
  }
  if (data.title.trim().length > MAX_TITLE_LENGTH) {
    throw new AppError(400, 'validation_error', `Title must not exceed ${MAX_TITLE_LENGTH} characters.`);
  }
  if (data.description && data.description.length > MAX_DESCRIPTION_LENGTH) {
    throw new AppError(400, 'validation_error', `Description must not exceed ${MAX_DESCRIPTION_LENGTH} characters.`);
  }
  if (!isValidCoordinate(data.latitude, data.longitude)) {
    throw new AppError(400, 'validation_error', 'Latitude must be between -90 and 90 and longitude between -180 and 180.');
  }
  if (data.geofenceRadiusMeters < MIN_GEOFENCE_RADIUS_METERS || data.geofenceRadiusMeters > MAX_GEOFENCE_RADIUS_METERS) {
    throw new AppError(400, 'validation_error', `Geofence radius must be between ${MIN_GEOFENCE_RADIUS_METERS} and ${MAX_GEOFENCE_RADIUS_METERS} meters.`);
  }
  if (!Number.isInteger(data.rewardPoints) || data.rewardPoints < MIN_REWARD_POINTS || data.rewardPoints > MAX_REWARD_POINTS) {
    throw new AppError(400, 'validation_error', `Reward must be a positive integer between ${MIN_REWARD_POINTS} and ${MAX_REWARD_POINTS} KP.`);
  }
  if (!(CROWN_HUNT_REPEAT_RULES as readonly string[]).includes(data.repeatRule)) {
    throw new AppError(400, 'validation_error', 'Invalid repeat rule.');
  }
  if (data.availableFrom) {
    const from = new Date(data.availableFrom);
    if (isNaN(from.getTime())) {
      throw new AppError(400, 'validation_error', 'availableFrom must be a valid ISO 8601 date.');
    }
    if (data.availableUntil) {
      const until = new Date(data.availableUntil);
      if (isNaN(until.getTime())) {
        throw new AppError(400, 'validation_error', 'availableUntil must be a valid ISO 8601 date.');
      }
      if (until <= from) {
        throw new AppError(400, 'validation_error', 'availableUntil must be later than availableFrom.');
      }
    }
  }
}
