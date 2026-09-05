/**
 * drives.stats — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-stats`.
 *
 * Statistics are free: aggregate ALL retained drives belonging to the caller.
 * History browsing still has its separate 5 / 90-day / unlimited tier policy.
 * This endpoint returns aggregates only, never drive IDs, routes or other users'
 * information. requireActiveActor preserves authentication and restriction checks.
 *
 * The optional monthStartMillis/monthEndMillis define the viewer's LOCAL
 * calendar month for the "this month" fields; they are validated strictly
 * against server time (driveStats-core.resolveMonthRange). Drives without a
 * valid date count toward lifetime figures but not the month figures.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { effectiveSubscriptionTierFromStoredRecord } from '../subscription/subscription-core';
import {
  buildDriveStatSample,
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
  // Longest/max figures require scanning the owner's whole drive set.
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const driveStats = onCall(CALLABLE_OPTS, async (request): Promise<DriveStatsResponse> => {
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

  // One owner-only projected scan produces a consistent set of aggregates.
  // No date ordering: legacy saves without createdAt still count. Tier is
  // retained in the response for existing clients, but never filters stats.
  // O(all retained drives) reads per load; the client caches the result for
  // its screen session. A future rollup can reduce reads for large accounts.
  const docsSnap = await db
    .collection('rides')
    .where('userId', '==', actor.uid)
    .select(
      'distanceMeters',
      'durationSeconds',
      'averageSpeedMetersPerSecond',
      'maxSpeedMetersPerSecond',
      'createdAt',
    )
    .get();

  // Malformed durations exclude the drive; absent dates exclude it only from
  // month figures. Missing distance/speed values do not corrupt sums/maxima.
  const samples: DriveStatSample[] = docsSnap.docs
    .map((doc) => {
      const data = doc.data();
      return buildDriveStatSample({
        distanceMeters: data.distanceMeters,
        durationSeconds: data.durationSeconds,
        averageSpeedMps: data.averageSpeedMetersPerSecond,
        maxSpeedMps: data.maxSpeedMetersPerSecond,
        createdAtMillis: data.createdAt instanceof Timestamp ? data.createdAt.toMillis() : null,
      });
    })
    .filter((sample): sample is DriveStatSample => sample != null);
  const totalDrives = samples.length;
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
});
