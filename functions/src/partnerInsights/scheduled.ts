/**
 * Partner insights scheduled functions (Phase 9j) — the migration's first
 * onSchedule Cloud Functions.
 *
 * - partnerInsights-aggregateDaily (03:00 Europe/Stockholm): aggregates
 *   yesterday's events into partnerInsights/{aggregateId} documents for
 *   every company that had events — per interaction type, for the day /
 *   week / month periods — with the anonymous_pass_by minimum-contributor
 *   threshold enforced (below threshold → counts ZEROED, status
 *   insufficient_data). The threshold floor is 10 and configuration can
 *   only raise it (config/partnerInsights.minThreshold).
 * - partnerInsights-cleanupExpired (04:00 Europe/Stockholm): deletes raw
 *   events whose expiresAt has passed (7-day TTL), in batches. Only the
 *   threshold-enforced aggregates persist beyond the TTL.
 *
 * The runInsightsAggregation/runInsightsCleanup runners are exported
 * separately so emulator tests can drive them deterministically.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import {
  AGGREGATION_PERIODS,
  PARTNER_INTERACTION_TYPES,
  aggregateId,
  computeAggregateMetric,
  effectiveThreshold,
  previousUtcDay,
  resolvePeriodBounds,
  type AggregationPeriod,
  type PartnerInteractionType,
} from './insights-core';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';

const CLEANUP_BATCH_SIZE = 500;

async function readConfiguredThreshold(): Promise<number> {
  try {
    const snap = await db.collection('config').doc('partnerInsights').get();
    return effectiveThreshold(snap.data()?.minThreshold);
  } catch (error) {
    logger.warn('Threshold config read failed; using the floor', { error: String(error) });
    return effectiveThreshold(undefined);
  }
}

/**
 * Aggregates all events overlapping the periods containing `date` for every
 * company that produced events that day. Deterministic aggregate IDs make
 * re-runs idempotent overwrites of the same documents.
 */
export async function runInsightsAggregation(date: Date): Promise<{ aggregatesWritten: number }> {
  const threshold = await readConfiguredThreshold();
  const dayBounds = resolvePeriodBounds(date, 'day');

  // Companies with events on the target day.
  const dayEvents = await db
    .collection('partnerInsightsEvents')
    .where('occurredAt', '>=', Timestamp.fromDate(dayBounds.start))
    .where('occurredAt', '<', Timestamp.fromDate(dayBounds.end))
    .select('companyId')
    .get();
  const companyIds = [...new Set(dayEvents.docs.map((d) => d.data().companyId as string))];

  let aggregatesWritten = 0;
  for (const companyId of companyIds) {
    for (const periodType of AGGREGATION_PERIODS) {
      const bounds = resolvePeriodBounds(date, periodType);
      const periodEvents = await db
        .collection('partnerInsightsEvents')
        .where('companyId', '==', companyId)
        .where('occurredAt', '>=', Timestamp.fromDate(bounds.start))
        .where('occurredAt', '<', Timestamp.fromDate(bounds.end))
        .select('interactionType', 'userReferenceHash')
        .get();

      const byType = new Map<string, { total: number; contributors: Set<string> }>();
      for (const doc of periodEvents.docs) {
        const type = doc.data().interactionType as string;
        const entry = byType.get(type) ?? { total: 0, contributors: new Set<string>() };
        entry.total += 1;
        const hash = doc.data().userReferenceHash;
        if (typeof hash === 'string' && hash.length > 0) {
          entry.contributors.add(hash);
        }
        byType.set(type, entry);
      }

      const batch = db.batch();
      for (const interactionType of PARTNER_INTERACTION_TYPES) {
        const entry = byType.get(interactionType);
        const metric = computeAggregateMetric(
          interactionType,
          entry?.total ?? 0,
          entry?.contributors.size ?? 0,
          threshold,
        );
        // no_data periods are skipped entirely — an all-zero document adds
        // nothing and would bloat the collection for every company × type.
        if (metric.resultStatus === 'no_data') {
          continue;
        }
        batch.set(
          db
            .collection('partnerInsights')
            .doc(aggregateId(companyId, interactionType, periodType, bounds.start)),
          {
            companyId,
            interactionType,
            periodType,
            periodStart: Timestamp.fromDate(bounds.start),
            periodEnd: Timestamp.fromDate(bounds.end),
            totalCount: metric.totalCount,
            uniqueContributorCount: metric.uniqueContributorCount,
            resultStatus: metric.resultStatus,
            updatedAt: Timestamp.fromDate(new Date()),
          },
        );
        aggregatesWritten += 1;
      }
      await batch.commit();
    }
  }

  logger.info('Partner insights aggregation complete', {
    companies: companyIds.length,
    aggregatesWritten,
  });
  return { aggregatesWritten };
}

/** Deletes expired raw events (7-day TTL), batch by batch. */
export async function runInsightsCleanup(now: Date): Promise<{ deletedCount: number }> {
  let deletedCount = 0;
  for (;;) {
    const expired = await db
      .collection('partnerInsightsEvents')
      .where('expiresAt', '<=', Timestamp.fromDate(now))
      .limit(CLEANUP_BATCH_SIZE)
      .get();
    if (expired.empty) {
      break;
    }
    const batch = db.batch();
    for (const doc of expired.docs) {
      batch.delete(doc.ref);
    }
    await batch.commit();
    deletedCount += expired.size;
    if (expired.size < CLEANUP_BATCH_SIZE) {
      break;
    }
  }

  logger.info('Partner insights cleanup complete', { deletedCount });
  return { deletedCount };
}

const SCHEDULE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_SCHEDULED,
  timeZone: 'Europe/Stockholm',
  memory: '256MiB' as const,
  timeoutSeconds: 300,
};

/** Daily aggregation over yesterday (all periods containing it). */
export const aggregateDaily = onSchedule(
  { ...SCHEDULE_OPTS, schedule: '0 3 * * *' },
  async () => {
    await runInsightsAggregation(previousUtcDay(new Date()));
  },
);

/** Daily TTL cleanup of raw events. */
export const cleanupExpired = onSchedule(
  { ...SCHEDULE_OPTS, schedule: '0 4 * * *' },
  async () => {
    await runInsightsCleanup(new Date());
  },
);

// Referenced for the registry description; keeps the type imported.
export type { AggregationPeriod, PartnerInteractionType };
