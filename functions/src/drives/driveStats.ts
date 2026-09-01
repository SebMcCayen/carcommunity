/**
 * drives.stats — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-stats`.
 *
 * Server-authoritative statistics aggregate over ONLY the caller's tier-visible
 * drives, using the exact same subscription policy as drives.listHistory
 * (Community = their latest 5, Plus = the rolling 90-day window, Supporter =
 * everything). Deeper statistics are therefore a paid benefit, consistent with
 * history visibility, and the aggregate can never widen access to drives the
 * read policy hides. Same actor gate as listHistory (requireActiveActor —
 * signed in, not suspended, not deleted; membership gating stays disabled so
 * Community may call it). Rules are intentionally unchanged in this slice.
 *
 * The optional monthStartMillis/monthEndMillis define the viewer's LOCAL
 * calendar month for the "this month" fields; they are validated strictly
 * against server time (driveStats-core.resolveMonthRange) and intersected with
 * the tier-visible set in memory, so a crafted month range can neither widen
 * access nor be trusted from the device clock.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import {
  FieldPath,
  Timestamp,
  type DocumentData,
  type Query,
} from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { effectiveSubscriptionTierFromStoredRecord } from '../subscription/subscription-core';
import { driveHistoryPolicyForTier } from './driveHistory-core';
import {
  parseDriveStatsInput,
  resolveMonthRange,
  scanDriveStats,
  type DriveStatSample,
  type DriveStatsResponse,
} from './driveStats-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  // Slightly longer than listHistory: the max/longest figures require scanning
  // the whole tier-visible set, which is unbounded for Supporter.
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

function nullableNonnegativeNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

export const driveStats = onCall(
  CALLABLE_OPTS,
  async (request): Promise<DriveStatsResponse> => {
    const actor = await requireActiveActor(request);
    const parsed = parseDriveStatsInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);

    const serverNowMillis = Date.now();
    const monthRange = resolveMonthRange(parsed.input, serverNowMillis);
    if (!monthRange.ok) throw new HttpsError('invalid-argument', monthRange.message);

    const subscriptionSnap = await db.collection('subscriptions').doc(actor.uid).get();
    const tier = effectiveSubscriptionTierFromStoredRecord(
      subscriptionSnap.exists ? subscriptionSnap.data() : null,
      actor.uid,
    );
    const policy = driveHistoryPolicyForTier(tier, serverNowMillis);

    // The tier-visible query — identical shape to drives.listHistory so it is
    // backed by the same composite index. Community's limit(5) lives INSIDE this
    // query, so every read below (the count and the scan) is confined to the
    // tier-visible set and can never widen access to hidden history.
    const ownerQuery = db.collection('rides').where('userId', '==', actor.uid);
    let visibleQuery: Query<DocumentData> = ownerQuery
      .orderBy('createdAt', 'desc')
      .orderBy(FieldPath.documentId(), 'desc');
    if (policy.kind === 'rolling_days') {
      visibleQuery = visibleQuery.where('createdAt', '>=', Timestamp.fromMillis(policy.cutoffMillis));
    }
    if (policy.kind === 'latest_count') {
      visibleQuery = visibleQuery.limit(policy.limit);
    }

    const [countSnap, docsSnap] = await Promise.all([
      // totalDrives via Firestore count() aggregation — cheap and index-backed by
      // the same index listHistory's count already uses. The distance/duration
      // SUMS are NOT aggregated: Firestore cannot index a two-field sum() next to
      // the tier ordering, and per-field sums would each need a hand-deployed
      // composite index (out of scope for this additive slice). They are computed
      // in the scan below instead — at no extra read cost, since it already reads
      // every tier-visible doc.
      visibleQuery.count().get(),
      // longest/fastest/highestMax, the distance/duration totals, and the month
      // intersection are all derived from this one scan. It is O(all visible
      // drives): cheap for Community (≤5) and Plus (90-day window), but for a
      // large Supporter account it reads every ride on every call. A later
      // optimization would maintain running maxima or a periodic rollup document
      // instead of scanning here — deliberately NOT done now.
      visibleQuery.get(),
    ]);

    const totalDrives = countSnap.data().count;

    const samples: DriveStatSample[] = docsSnap.docs.map((doc) => {
      const data = doc.data();
      return {
        distanceMeters: nullableNonnegativeNumber(data.distanceMeters),
        // Same guard drives.listHistory applies: a corrupt negative or
        // non-integer durationSeconds must not flow into totalDurationSeconds
        // (schema minimum 0) — treat anything invalid as 0.
        durationSeconds:
          typeof data.durationSeconds === 'number' &&
          Number.isSafeInteger(data.durationSeconds) &&
          data.durationSeconds >= 0
            ? data.durationSeconds
            : 0,
        averageSpeedMps: nullableNonnegativeNumber(data.averageSpeedMetersPerSecond),
        maxSpeedMps: nullableNonnegativeNumber(data.maxSpeedMetersPerSecond),
        createdAtMillis: data.createdAt instanceof Timestamp ? data.createdAt.toMillis() : 0,
      };
    });
    const scanned = scanDriveStats(samples, monthRange.range);

    return {
      tier,
      serverNowMillis,
      totalDrives,
      totalDistanceMeters: scanned.totalDistanceMeters,
      totalDurationSeconds: scanned.totalDurationSeconds,
      longestDriveMeters: scanned.longestDriveMeters,
      averageDriveMeters: totalDrives > 0 ? scanned.totalDistanceMeters / totalDrives : 0,
      fastestAverageSpeedMps: scanned.fastestAverageSpeedMps,
      highestMaxSpeedMps: scanned.highestMaxSpeedMps,
      thisMonthDrives: scanned.thisMonthDrives,
      thisMonthDistanceMeters: scanned.thisMonthDistanceMeters,
    };
  },
);
