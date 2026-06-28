import { createHash } from 'node:crypto';

import type { PrismaClient } from '@prisma/client';
import {
  AGGREGATION_PERIODS,
  INTERACTION_EVENT_TTL_DAYS,
  MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
  PARTNER_INTERACTION_TYPES,
  PASS_BY_CONTRIBUTION_TTL_DAYS,
  PASS_BY_RADIUS_METERS,
  type AdminInsightsPeriod,
  type AggregationPeriod,
  type AnonymousPassByAggregationResult,
  type InsightResultStatus,
  type PartnerInsightsMetric,
  type PartnerInsightsSummary,
  type PartnerInsightsTimeSeriesBucket,
  type PartnerInteractionType,
  type PrivacyThresholdResult,
} from '@carcommunity/shared/partner-insights';
import {
  canContributeAnonymousPartnerStats,
  type UserStatus,
} from '@carcommunity/shared/users';

import type { AppConfig } from '../config.js';
import { AppError } from './errors.js';

const DEFAULT_BATCH_SIZE = 500;
const PARTNER_INSIGHTS_HASH_PREFIX = 'kcc-pi';

export interface RecordInteractionInput {
  partnerCompanyId: string;
  interactionType: PartnerInteractionType;
  userId?: string;
  userStatus?: UserStatus;
  anonymousPartnerStatsOptIn?: boolean;
  relatedOfferId?: string;
  idempotencyKey?: string;
  now?: Date;
}

export interface RecordAnonymousPassByInput {
  partnerCompanyId: string;
  userId: string;
  userStatus: UserStatus;
  anonymousPartnerStatsOptIn: boolean;
  latitude?: number;
  longitude?: number;
  now?: Date;
}

interface PeriodBounds {
  start: Date;
  end: Date;
}

interface AdminInsightsWindow extends PeriodBounds {
  preferredPeriodTypes: AggregationPeriod[];
}

function startOfUtcDay(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

function addUtcDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setUTCDate(result.getUTCDate() + days);
  return result;
}

function startOfUtcMonth(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1));
}

function addUtcMonths(date: Date, months: number): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + months, date.getUTCDate()));
}

function startOfIsoWeek(date: Date): Date {
  const day = date.getUTCDay() || 7;
  return addUtcDays(startOfUtcDay(date), 1 - day);
}

function endExclusiveForPeriod(start: Date, periodType: AggregationPeriod): Date {
  switch (periodType) {
    case 'day':
      return addUtcDays(start, 1);
    case 'week':
      return addUtcDays(start, 7);
    case 'month':
      return new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 1));
    default:
      return addUtcDays(start, 1);
  }
}

function resolvePeriodBounds(date: Date, periodType: AggregationPeriod): PeriodBounds {
  switch (periodType) {
    case 'day': {
      const start = startOfUtcDay(date);
      return { start, end: endExclusiveForPeriod(start, periodType) };
    }
    case 'week': {
      const start = startOfIsoWeek(date);
      return { start, end: endExclusiveForPeriod(start, periodType) };
    }
    case 'month': {
      const start = startOfUtcMonth(date);
      return { start, end: endExclusiveForPeriod(start, periodType) };
    }
    default: {
      const start = startOfUtcDay(date);
      return { start, end: endExclusiveForPeriod(start, 'day') };
    }
  }
}

function resolveAdminInsightsWindow(period: AdminInsightsPeriod, now: Date): AdminInsightsWindow {
  const today = startOfUtcDay(now);

  switch (period) {
    case 'last_7_days':
      return { start: addUtcDays(today, -6), end: addUtcDays(today, 1), preferredPeriodTypes: ['day'] };
    case 'last_30_days':
      return { start: addUtcDays(today, -29), end: addUtcDays(today, 1), preferredPeriodTypes: ['day', 'week'] };
    case 'current_month': {
      const start = startOfUtcMonth(now);
      return { start, end: addUtcMonths(start, 1), preferredPeriodTypes: ['week', 'month', 'day'] };
    }
    case 'previous_month': {
      const end = startOfUtcMonth(now);
      const start = addUtcMonths(end, -1);
      return { start, end, preferredPeriodTypes: ['week', 'month', 'day'] };
    }
    default:
      return { start: addUtcDays(today, -29), end: addUtcDays(today, 1), preferredPeriodTypes: ['day', 'week'] };
  }
}

function toIsoDateString(date: Date): string {
  return date.toISOString();
}

function sanitizeMetric(metric: PartnerInsightsMetric): PartnerInsightsMetric {
  if (metric.status === 'insufficient_data') {
    return {
      ...metric,
      totalCount: 0,
      uniqueContributorCount: undefined,
    };
  }

  if (metric.status === 'no_data') {
    return {
      ...metric,
      totalCount: 0,
      uniqueContributorCount: undefined,
    };
  }

  return metric;
}

function buildDefaultMetric(
  interactionType: PartnerInteractionType,
  periodStart: Date,
  periodEnd: Date,
): PartnerInsightsMetric {
  return {
    interactionType,
    totalCount: 0,
    periodStart: toIsoDateString(periodStart),
    periodEnd: toIsoDateString(periodEnd),
    status: 'no_data',
  };
}

function buildScopedHash(partnerId: string, userId: string): string {
  return createHash('sha256')
    .update(`${PARTNER_INSIGHTS_HASH_PREFIX}:${partnerId}:${userId}`)
    .digest('hex')
    .slice(0, 64);
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRadians = (value: number) => (value * Math.PI) / 180;
  const earthRadiusMeters = 6_371_000;
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLon / 2) ** 2;

  return 2 * earthRadiusMeters * Math.asin(Math.sqrt(a));
}

export class PartnerInsightsService {
  private readonly passByFeatureEnabled: boolean;
  private readonly minThreshold: number;

  constructor(
    private readonly prisma: PrismaClient,
    config?: Pick<AppConfig, 'partnerInsightsPassByFeatureEnabled' | 'partnerInsightsMinThreshold'>,
  ) {
    this.passByFeatureEnabled = config?.partnerInsightsPassByFeatureEnabled ?? false;
    this.minThreshold = Math.max(
      MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
      config?.partnerInsightsMinThreshold ?? MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
    );
  }

  async recordInteraction(input: RecordInteractionInput): Promise<{ recorded: boolean }> {
    if (!PARTNER_INTERACTION_TYPES.includes(input.interactionType)) {
      throw new AppError(400, 'interaction_type_unsupported', 'Unsupported interaction type.');
    }

    if (input.interactionType === 'anonymous_pass_by') {
      if (!this.passByFeatureEnabled) {
        throw new AppError(403, 'feature_disabled', 'Anonymous pass-by collection is disabled.');
      }

      const userStatus = input.userStatus ?? 'deleted';
      const optedIn = input.anonymousPartnerStatsOptIn ?? false;
      if (!canContributeAnonymousPartnerStats({ status: userStatus, anonymousPartnerStatsOptIn: optedIn })) {
        return { recorded: false };
      }
    }

    const partner = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerCompanyId },
      select: {
        id: true,
        status: true,
      },
    });

    if (!partner) {
      throw new AppError(404, 'not_found', 'Partner company not found.');
    }

    if (partner.status !== 'active') {
      throw new AppError(403, 'interaction_partner_inactive', 'Partner company is not active.');
    }

    if (input.relatedOfferId) {
      const offer = await this.prisma.partnerOffer.findUnique({
        where: { id: input.relatedOfferId },
        select: {
          id: true,
          partnerCompanyId: true,
        },
      });

      if (!offer) {
        throw new AppError(404, 'interaction_offer_not_found', 'Related offer not found.');
      }

      if (offer.partnerCompanyId !== input.partnerCompanyId) {
        throw new AppError(
          400,
          'interaction_offer_partner_mismatch',
          'Related offer does not belong to the selected partner.',
        );
      }
    }

    const now = input.now ?? new Date();
    const aggregationDate = startOfUtcDay(now);
    const expiresAt = addUtcDays(now, INTERACTION_EVENT_TTL_DAYS);
    const userReferenceHash = input.userId ? buildScopedHash(input.partnerCompanyId, input.userId) : null;

    const duplicate = await this.prisma.partnerInteractionEvent.findFirst({
      where: {
        partnerCompanyId: input.partnerCompanyId,
        interactionType: input.interactionType,
        aggregationDate,
        userReferenceHash,
      },
      select: { id: true },
    });

    if (duplicate) {
      return { recorded: false };
    }

    const metadata: Record<string, string> = {};
    if (input.relatedOfferId) {
      metadata.relatedOfferId = input.relatedOfferId;
    }
    if (input.idempotencyKey) {
      metadata.idempotencyKey = input.idempotencyKey;
    }

    await this.prisma.partnerInteractionEvent.create({
      data: {
        partnerCompanyId: input.partnerCompanyId,
        interactionType: input.interactionType,
        userReferenceHash,
        occurredAt: now,
        aggregationDate,
        expiresAt,
        metadata: Object.keys(metadata).length > 0 ? metadata : undefined,
      },
    });

    return { recorded: true };
  }

  async recordAnonymousPassBy(input: RecordAnonymousPassByInput): Promise<AnonymousPassByAggregationResult> {
    if (!this.passByFeatureEnabled) {
      return { counted: false, reason: 'feature_disabled' };
    }

    if (
      !canContributeAnonymousPartnerStats({
        status: input.userStatus,
        anonymousPartnerStatsOptIn: input.anonymousPartnerStatsOptIn,
      })
    ) {
      return { counted: false, reason: 'opted_out' };
    }

    const now = input.now ?? new Date();
    const aggregationDate = startOfUtcDay(now);

    const partner = await this.prisma.partnerCompany.findUnique({
      where: { id: input.partnerCompanyId },
      select: {
        id: true,
        status: true,
        latitude: true,
        longitude: true,
      },
    });

    if (!partner || partner.status !== 'active') {
      return { counted: false, reason: 'partner_inactive' };
    }

    const latestPosition =
      input.latitude != null && input.longitude != null
        ? { latitude: input.latitude, longitude: input.longitude }
        : await this.prisma.liveLocationLatestPosition.findFirst({
            where: {
              userId: input.userId,
              session: {
                status: 'active',
                expiresAt: { gt: now },
              },
            },
            select: {
              latitude: true,
              longitude: true,
            },
          });

    if (!latestPosition) {
      return { counted: false, reason: 'no_active_location' };
    }

    // Out-of-radius pass-bys are intentionally dropped without recording extra location detail.
    if (
      haversineMeters(
        latestPosition.latitude,
        latestPosition.longitude,
        partner.latitude,
        partner.longitude,
      ) > PASS_BY_RADIUS_METERS
    ) {
      return { counted: false, reason: 'threshold_pending' };
    }

    const scopedContributorHash = buildScopedHash(input.partnerCompanyId, input.userId);
    const existingContribution = await this.prisma.partnerPassByContribution.findUnique({
      where: {
        partnerCompanyId_scopedContributorHash_aggregationDate: {
          partnerCompanyId: input.partnerCompanyId,
          scopedContributorHash,
          aggregationDate,
        },
      },
      select: { id: true },
    });

    if (existingContribution) {
      return { counted: false, reason: 'already_counted' };
    }

    await this.prisma.$transaction(async (tx) => {
      await tx.partnerPassByContribution.create({
        data: {
          partnerCompanyId: input.partnerCompanyId,
          scopedContributorHash,
          aggregationDate,
          expiresAt: addUtcDays(now, PASS_BY_CONTRIBUTION_TTL_DAYS),
        },
      });

      await tx.partnerInteractionEvent.create({
        data: {
          partnerCompanyId: input.partnerCompanyId,
          interactionType: 'anonymous_pass_by',
          userReferenceHash: scopedContributorHash,
          occurredAt: now,
          aggregationDate,
          expiresAt: addUtcDays(now, INTERACTION_EVENT_TTL_DAYS),
        },
      });
    });

    return { counted: true, reason: 'success' };
  }

  checkThreshold(uniqueContributorCount: number, minThreshold = this.minThreshold): PrivacyThresholdResult {
    const meetsThreshold = uniqueContributorCount >= minThreshold;
    return {
      meetsThreshold,
      suppressedCount: !meetsThreshold,
    };
  }

  async aggregatePeriod(input: {
    partnerCompanyId: string;
    date: Date;
    periodType: AggregationPeriod;
    minThreshold?: number;
  }): Promise<void> {
    if (!AGGREGATION_PERIODS.includes(input.periodType)) {
      throw new AppError(400, 'validation_error', 'Unsupported aggregation period.');
    }

    const threshold = input.minThreshold ?? this.minThreshold;
    const bounds = resolvePeriodBounds(input.date, input.periodType);

    await this.prisma.$transaction(async (tx) => {
      for (const interactionType of PARTNER_INTERACTION_TYPES) {
        const events = await tx.partnerInteractionEvent.findMany({
          where: {
            partnerCompanyId: input.partnerCompanyId,
            interactionType,
            occurredAt: {
              gte: bounds.start,
              lt: bounds.end,
            },
          },
          select: {
            userReferenceHash: true,
          },
        });

        const totalCount = events.length;
        const uniqueContributorCount = new Set(
          events
            .map((event) => event.userReferenceHash)
            .filter((value): value is string => typeof value === 'string' && value.length > 0),
        ).size;

        let resultStatus: InsightResultStatus = totalCount > 0 ? 'available' : 'no_data';
        let persistedTotalCount = totalCount;
        let persistedUniqueContributorCount: number | null = totalCount > 0 ? uniqueContributorCount : null;

        if (interactionType === 'anonymous_pass_by' && totalCount > 0) {
          const thresholdResult = this.checkThreshold(uniqueContributorCount, threshold);
          if (!thresholdResult.meetsThreshold) {
            resultStatus = 'insufficient_data';
            persistedTotalCount = 0;
            persistedUniqueContributorCount = null;
          }
        } else if (totalCount === 0) {
          persistedUniqueContributorCount = null;
        }

        await tx.partnerMetricAggregate.upsert({
          where: {
            partnerCompanyId_interactionType_periodType_periodStart: {
              partnerCompanyId: input.partnerCompanyId,
              interactionType,
              periodType: input.periodType,
              periodStart: bounds.start,
            },
          },
          create: {
            partnerCompanyId: input.partnerCompanyId,
            interactionType,
            periodType: input.periodType,
            periodStart: bounds.start,
            periodEnd: bounds.end,
            totalCount: persistedTotalCount,
            uniqueContributorCount: persistedUniqueContributorCount,
            resultStatus,
          },
          update: {
            periodEnd: bounds.end,
            totalCount: persistedTotalCount,
            uniqueContributorCount: persistedUniqueContributorCount,
            resultStatus,
          },
        });
      }
    });
  }

  async getAdminInsights(input: {
    partnerId: string;
    period: AdminInsightsPeriod;
    minThreshold?: number;
  }): Promise<{
    partnerId: string;
    period: AdminInsightsPeriod;
    buckets: PartnerInsightsTimeSeriesBucket[];
    generatedAt: string;
  }> {
    const now = new Date();
    const window = resolveAdminInsightsWindow(input.period, now);
    await this.assertPartnerExists(input.partnerId);

    const rows = await this.selectAggregateRows(input.partnerId, window);
    const bucketMap = new Map<string, PartnerInsightsTimeSeriesBucket>();

    for (const row of rows) {
      const bucketKey = `${row.periodType}:${row.periodStart.toISOString()}`;
      const existing = bucketMap.get(bucketKey);
      const metric = sanitizeMetric({
        interactionType: row.interactionType as PartnerInteractionType,
        totalCount: row.totalCount,
        uniqueContributorCount: row.uniqueContributorCount ?? undefined,
        periodStart: row.periodStart.toISOString(),
        periodEnd: row.periodEnd.toISOString(),
        status: row.resultStatus as InsightResultStatus,
      });

      if (existing) {
        existing.metrics = existing.metrics.map((candidate) =>
          candidate.interactionType === metric.interactionType ? metric : candidate,
        );
        continue;
      }

      const periodStart = row.periodStart;
      const periodEnd = row.periodEnd;
      bucketMap.set(bucketKey, {
        periodStart: periodStart.toISOString(),
        periodEnd: periodEnd.toISOString(),
        periodType: row.periodType as AggregationPeriod,
        metrics: PARTNER_INTERACTION_TYPES.map((interactionType) =>
          interactionType === metric.interactionType
            ? metric
            : buildDefaultMetric(interactionType, periodStart, periodEnd),
        ),
      });
    }

    return {
      partnerId: input.partnerId,
      period: input.period,
      buckets: [...bucketMap.values()].sort((a, b) => a.periodStart.localeCompare(b.periodStart)),
      generatedAt: now.toISOString(),
    };
  }

  async getAdminInsightsSummary(input: {
    partnerId: string;
    period: AdminInsightsPeriod;
    minThreshold?: number;
  }): Promise<PartnerInsightsSummary> {
    const now = new Date();
    const window = resolveAdminInsightsWindow(input.period, now);
    await this.assertPartnerExists(input.partnerId);

    const rows = await this.selectAggregateRows(input.partnerId, window);
    const metrics = PARTNER_INTERACTION_TYPES.map((interactionType) => {
      const matchingRows = rows.filter((row) => row.interactionType === interactionType);

      if (matchingRows.length === 0) {
        return buildDefaultMetric(interactionType, window.start, window.end);
      }

      const hasInsufficientData = matchingRows.some((row) => row.resultStatus === 'insufficient_data');
      const hasAvailableData = matchingRows.some((row) => row.resultStatus === 'available');

      if (interactionType === 'anonymous_pass_by' && hasInsufficientData && !hasAvailableData) {
        return sanitizeMetric({
          interactionType,
          totalCount: 0,
          periodStart: window.start.toISOString(),
          periodEnd: window.end.toISOString(),
          status: 'insufficient_data',
        });
      }

      const totalCount = matchingRows.reduce((sum, row) => sum + row.totalCount, 0);
      const uniqueContributorCount = matchingRows.reduce(
        (max, row) => Math.max(max, row.uniqueContributorCount ?? 0),
        0,
      );
      const status: InsightResultStatus = totalCount > 0 ? 'available' : hasInsufficientData ? 'insufficient_data' : 'no_data';

      return sanitizeMetric({
        interactionType,
        totalCount,
        uniqueContributorCount: status === 'available' && uniqueContributorCount > 0 ? uniqueContributorCount : undefined,
        periodStart: window.start.toISOString(),
        periodEnd: window.end.toISOString(),
        status,
      });
    });

    return {
      partnerId: input.partnerId,
      period: input.period,
      metrics,
      generatedAt: now.toISOString(),
    };
  }

  async cleanupExpiredEvents(batchSize = DEFAULT_BATCH_SIZE): Promise<{
    deletedEventCount: number;
    deletedContributionCount: number;
  }> {
    const safeBatchSize = Math.max(1, batchSize);
    const now = new Date();

    const expiredEvents = await this.prisma.partnerInteractionEvent.findMany({
      where: { expiresAt: { lte: now } },
      select: { id: true },
      take: safeBatchSize,
      orderBy: { expiresAt: 'asc' },
    });

    const expiredContributions = await this.prisma.partnerPassByContribution.findMany({
      where: { expiresAt: { lte: now } },
      select: { id: true },
      take: safeBatchSize,
      orderBy: { expiresAt: 'asc' },
    });

    const deletedEventCount =
      expiredEvents.length > 0
        ? (
            await this.prisma.partnerInteractionEvent.deleteMany({
              where: { id: { in: expiredEvents.map((row) => row.id) } },
            })
          ).count
        : 0;

    const deletedContributionCount =
      expiredContributions.length > 0
        ? (
            await this.prisma.partnerPassByContribution.deleteMany({
              where: { id: { in: expiredContributions.map((row) => row.id) } },
            })
          ).count
        : 0;

    return {
      deletedEventCount,
      deletedContributionCount,
    };
  }

  private async assertPartnerExists(partnerId: string): Promise<void> {
    const partner = await this.prisma.partnerCompany.findUnique({
      where: { id: partnerId },
      select: { id: true },
    });

    if (!partner) {
      throw new AppError(404, 'insights_partner_not_found', 'Partner company not found.');
    }
  }

  private async selectAggregateRows(partnerId: string, window: AdminInsightsWindow) {
    for (const periodType of window.preferredPeriodTypes) {
      const rows = await this.prisma.partnerMetricAggregate.findMany({
        where: {
          partnerCompanyId: partnerId,
          periodType,
          periodStart: { gte: window.start },
          periodEnd: { lte: window.end },
        },
        orderBy: [{ periodStart: 'asc' }, { interactionType: 'asc' }],
      });

      if (rows.length > 0) {
        return rows;
      }
    }

    return [];
  }
}
