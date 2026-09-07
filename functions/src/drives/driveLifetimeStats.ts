/**
 * drives.lifetimeStats — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-lifetimeStats`.
 *
 * TRUE-LIFETIME statistics aggregate over ALL of the caller's drives, with NO
 * subscription-tier window and NO month range. Like drives.stats, this is free
 * for every tier: the profile "my stats" fold and the badge system
 * (e.g. the Vägfarare lifetime-distance badge) measure genuine lifetime
 * achievement, which must not shrink when a member downgrades to Community.
 *
 * Owner-only: aggregates rides where userId == the caller's uid. The actor gate
 * is requireActiveActor (signed in, not suspended, not deleted) with NO tier or
 * membership assertion, so a Community member gets their true totals. Rules are
 * intentionally unchanged in this slice — Android's profile still reads `rides`
 * directly for these figures today; the migration to consume this callable and
 * the subsequent direct-read lockdown are SEPARATE later slices.
 *
 * No input (an empty object, or any input, is ignored).
 */

import { onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import {
  buildLifetimeSample,
  scanLifetimeStats,
  type DriveLifetimeStatsResponse,
  type LifetimeDriveSample,
} from './driveLifetimeStats-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  // Same rationale as drives.stats: the max/longest figures require scanning the
  // owner's whole drive set, which is unbounded for a heavy account.
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const driveLifetimeStats = onCall(
  CALLABLE_OPTS,
  async (request): Promise<DriveLifetimeStatsResponse> => {
    const actor = await requireActiveActor(request);
    const serverNowMillis = Date.now();

    // ONE projected scan of the owner's rides (NO tier window — every ride the
    // caller owns) is the SOLE source for every figure: totalDrives, the sums
    // and the maxima all come from this same snapshot, so they can never be
    // internally inconsistent (an earlier separate count() aggregation was both
    // a redundant billed read and an add/remove-between-reads consistency risk —
    // mirrors drives.stats, which derives totalDrives from the same scan too).
    // Project only the scalar stat fields the reducer reads — never the heavy
    // routeThumbnail/convoyMembers — so even a large account stays cheap.
    // createdAt is deliberately NOT read: this aggregate has no month window and
    // no tier ordering, so legacy drives lacking createdAt still count.
    //
    // Read-cost note (same standing note as drives.stats): this reads every ride
    // the caller owns on every call, which is O(all drives) — cheap for a normal
    // account but unbounded for a very large one. A future optimization would
    // maintain a periodic rollup document (or running counters) instead of
    // scanning here; deliberately not now, to keep this slice additive.
    const docsSnap = await db
      .collection('rides')
      .where('userId', '==', actor.uid)
      .select(
        'distanceMeters',
        'durationSeconds',
        'averageSpeedMetersPerSecond',
        'maxSpeedMetersPerSecond',
      )
      .get();

    // Field validation, then drop any malformed drive so it can never corrupt
    // the aggregate (mirrors driveStats-core). totalDrives is therefore the
    // count of VALID drives in this one snapshot — the same definition
    // drives.stats uses.
    const samples: LifetimeDriveSample[] = docsSnap.docs
      .map((doc) => {
        const data = doc.data();
        return buildLifetimeSample({
          distanceMeters: data.distanceMeters,
          durationSeconds: data.durationSeconds,
          averageSpeedMps: data.averageSpeedMetersPerSecond,
          maxSpeedMps: data.maxSpeedMetersPerSecond,
        });
      })
      .filter((sample): sample is LifetimeDriveSample => sample != null);
    const totalDrives = samples.length;
    const scanned = scanLifetimeStats(samples);

    return {
      serverNowMillis,
      totalDrives,
      totalDistanceMeters: scanned.totalDistanceMeters,
      totalDurationSeconds: scanned.totalDurationSeconds,
      longestDriveMeters: scanned.longestDriveMeters,
      averageDriveMeters: totalDrives > 0 ? scanned.totalDistanceMeters / totalDrives : 0,
      fastestAverageSpeedMps: scanned.fastestAverageSpeedMps,
      highestMaxSpeedMps: scanned.highestMaxSpeedMps,
    };
  },
);
