/**
 * partnerInsights.adminSummary — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `partnerInsights` export group as
 * `partnerInsights-adminSummary`.
 *
 * The only read path for partner insights: partnerInsights and
 * partnerInsightsEvents are backend-only (firestore.rules read,write:false) —
 * even for admins — because the raw events carry partner-scoped user hashes and
 * the aggregates are privacy-sensitive. This callable exposes the
 * threshold-enforced per-interaction-type aggregates for one company/period to
 * admins, re-applying the minimum-unique-contributor floor at read time
 * (defense in depth). No user-level data is ever returned.
 *
 * Aggregates are looked up by deterministic id (companyId × type × periodType ×
 * periodStart) via getAll — no query, no collectionGroup index.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import type { DocumentReference } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import {
  PARTNER_INTERACTION_TYPES,
  aggregateId,
  effectiveThreshold,
  resolvePeriodBounds,
  toIsoDate,
} from './insights-core';
import {
  applyReadThreshold,
  coerceResultStatus,
  parseAdminInsightsSummaryInput,
  type PartnerInsightsMetricOut,
  type StoredAggregateMetric,
} from './insights-admin-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const DAY_MS = 24 * 60 * 60 * 1000;

export interface AdminInsightsSummaryResult {
  companyId: string;
  periodType: string;
  periodStart: string;
  metrics: PartnerInsightsMetricOut[];
}

async function readEffectiveThreshold(): Promise<number> {
  try {
    const snap = await db.collection('config').doc('partnerInsights').get();
    return effectiveThreshold(snap.data()?.minThreshold);
  } catch {
    // Config read failure falls back to the absolute floor — never lower.
    return effectiveThreshold(undefined);
  }
}

export const adminSummary = onCall(
  CALLABLE_OPTS,
  async (request): Promise<AdminInsightsSummaryResult> => {
    await requireAdminActor(request);

    const parsed = parseAdminInsightsSummaryInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { companyId } = parsed.input;
    const periodType = parsed.input.periodType ?? 'day';
    // Default to the last aggregated day (aggregation runs for "yesterday").
    const referenceDate = parsed.input.date ? new Date(parsed.input.date) : new Date(Date.now() - DAY_MS);
    const bounds = resolvePeriodBounds(referenceDate, periodType);

    const threshold = await readEffectiveThreshold();

    const refs: DocumentReference[] = PARTNER_INTERACTION_TYPES.map((interactionType) =>
      db
        .collection('partnerInsights')
        .doc(aggregateId(companyId, interactionType, periodType, bounds.start)),
    );
    const snaps = await db.getAll(...refs);

    const metrics = PARTNER_INTERACTION_TYPES.map((interactionType, index) => {
      const snap = snaps[index];
      const data = snap?.exists ? snap.data() : undefined;
      const stored: StoredAggregateMetric | null = data
        ? {
            totalCount: (data.totalCount as number | undefined) ?? 0,
            uniqueContributorCount: (data.uniqueContributorCount as number | null | undefined) ?? null,
            // Untrusted stored value — coerce to a known status, failing closed
            // to no_data if the aggregate doc is corrupt/legacy.
            resultStatus: coerceResultStatus(data.resultStatus),
          }
        : null;
      return applyReadThreshold(interactionType, stored, threshold);
    });

    return {
      companyId,
      periodType,
      periodStart: toIsoDate(bounds.start),
      metrics,
    };
  },
);
