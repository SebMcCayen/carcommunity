/**
 * Partner DRIVE HEATMAP aggregation — the scheduled I/O job (Phase: partner
 * drive heat MVP).
 *
 * Once a day, over a rolling {@link DRIVE_HEAT_WINDOW_DAYS}-day window of
 * completed drives from CONSENTED users, this:
 *  1. lists `rides/{rideId}` in the window,
 *  2. keeps only rides whose owner has `anonymousPartnerStatsOptIn === true`
 *     (userPrivate/{uid}) — the SAME consent flag the partner-stats pass-by
 *     feature gates on,
 *  3. downloads each `rideRoutes/{uid}/{rideId}/route.bin`, decodes the driven
 *     track (route-codec.ts), trims the endpoints (home/work reveal), and bins
 *     the surviving points into H3 cells (drive-heat-core.ts),
 *  4. tallies UNIQUE contributors + total traversals per cell, DROPS every cell
 *     below the ≥10 unique-contributor floor, and
 *  5. writes ONE anonymised aggregate document `partnerDriveHeat/current` whose
 *     cells hold only `{ h3Index, contributorCount, weight }`.
 *
 * The pure binning/trim/threshold logic lives in drive-heat-core.ts; this file
 * is only the Firestore + Cloud Storage wiring. `runDriveHeatAggregation` is
 * exported so an emulator test can drive it deterministically.
 *
 * PRIVACY: the aggregate is backend-only (firestore.rules deny all client
 * reads/writes on partnerDriveHeat) and holds no user ids, no coordinates
 * beyond public H3 indexes, and no timestamps. Live sessions are deliberately
 * NOT a source: they keep no path, only a latest position.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db, adminStorage } from '../firebase';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';
import { decodeRoute } from '../drives/route-codec';
import { rideRoutePath } from '../drives/drives-core';
import {
  DriveHeatAccumulator,
  routeCells,
  trimRouteEndpoints,
  DRIVE_HEAT_H3_RESOLUTION,
  ENDPOINT_TRIM_METERS,
  MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
  type DriveHeatCell,
} from './drive-heat-core';

/** Rolling window of completed drives folded into the heatmap. */
export const DRIVE_HEAT_WINDOW_DAYS = 90;

/** Firestore document holding the single current anonymised aggregate. */
export const DRIVE_HEAT_DOC_PATH = { collection: 'partnerDriveHeat', doc: 'current' } as const;

/**
 * Cap on cells written to the single aggregate document, so it can never
 * approach Firestore's 1 MiB limit (each cell is ~40 bytes ⇒ 20 000 ≈ 0.8 MiB).
 * Cells are sorted by weight, so the cap keeps the busiest areas — exactly the
 * ones a billboard buyer cares about. Beyond this scale, move to per-cell docs.
 */
export const DRIVE_HEAT_MAX_CELLS = 20_000;

/** How many rides to page through Firestore at a time. */
const RIDE_PAGE_SIZE = 500;

/**
 * Aggregates the rolling window ending at `now` into the anonymised heat
 * document. Returns a small summary for logging/tests. Idempotent: it fully
 * overwrites `partnerDriveHeat/current` each run.
 */
export async function runDriveHeatAggregation(now: Date): Promise<{
  ridesConsidered: number;
  ridesContributing: number;
  cellsWritten: number;
}> {
  const cutoff = new Date(now.getTime() - DRIVE_HEAT_WINDOW_DAYS * 24 * 60 * 60 * 1000);
  const bucket = adminStorage.bucket();

  // Consent is looked up once per user and cached across all their rides.
  const consentCache = new Map<string, boolean>();
  const hasConsent = async (uid: string): Promise<boolean> => {
    const cached = consentCache.get(uid);
    if (cached !== undefined) return cached;
    let allowed = false;
    try {
      const snap = await db.collection('userPrivate').doc(uid).get();
      allowed = snap.data()?.anonymousPartnerStatsOptIn === true;
    } catch (error) {
      logger.warn('drive-heat: consent read failed; excluding user', { error: String(error) });
      allowed = false;
    }
    consentCache.set(uid, allowed);
    return allowed;
  };

  // Fold each drive into the accumulator as we page, so peak memory is bounded
  // by the number of distinct cells (the aggregate size), NOT by the number of
  // rides in the 90-day window — see DriveHeatAccumulator.
  const accumulator = new DriveHeatAccumulator();
  let ridesConsidered = 0;
  let ridesContributing = 0;

  // Page through rides in the window by startedAt (single-field range → the
  // automatic index covers it; no composite index to hand-deploy).
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | null = null;
  for (;;) {
    let query = db
      .collection('rides')
      .where('startedAt', '>=', Timestamp.fromDate(cutoff))
      .orderBy('startedAt', 'asc')
      .limit(RIDE_PAGE_SIZE);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    if (page.empty) break;

    for (const doc of page.docs) {
      ridesConsidered += 1;
      const data = doc.data();
      const uid = data.userId as string | undefined;
      if (!uid) continue;
      if (!(await hasConsent(uid))) continue;

      const cells = await cellsForRide(bucket, uid, doc.id);
      if (cells.length === 0) continue;
      accumulator.add(uid, cells);
      ridesContributing += 1;
    }

    if (page.size < RIDE_PAGE_SIZE) break;
    cursor = page.docs[page.size - 1]!;
  }

  const allCells = accumulator.finalize(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD);
  const cells: DriveHeatCell[] = allCells.slice(0, DRIVE_HEAT_MAX_CELLS);

  await db
    .collection(DRIVE_HEAT_DOC_PATH.collection)
    .doc(DRIVE_HEAT_DOC_PATH.doc)
    .set({
      // Document-level metadata only — NOT per-user data.
      generatedAt: Timestamp.fromDate(now),
      windowDays: DRIVE_HEAT_WINDOW_DAYS,
      resolution: DRIVE_HEAT_H3_RESOLUTION,
      trimMeters: ENDPOINT_TRIM_METERS,
      threshold: MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
      cellCount: cells.length,
      // Each cell carries ONLY the anonymised triple.
      cells: cells.map((c) => ({
        h3Index: c.h3Index,
        contributorCount: c.contributorCount,
        weight: c.weight,
      })),
    });

  logger.info('drive-heat aggregation complete', {
    ridesConsidered,
    ridesContributing,
    cellsWritten: cells.length,
    windowDays: DRIVE_HEAT_WINDOW_DAYS,
  });
  return { ridesConsidered, ridesContributing, cellsWritten: cells.length };
}

/**
 * Downloads and decodes one ride's route file and returns its trimmed,
 * de-duplicated H3 cells (or an empty array when the file is missing, corrupt,
 * summary-only, or entirely inside the endpoint-trim zones).
 */
async function cellsForRide(
  bucket: ReturnType<typeof adminStorage.bucket>,
  uid: string,
  rideId: string,
): Promise<string[]> {
  const file = bucket.file(rideRoutePath(uid, rideId));
  let bytes: Uint8Array;
  try {
    const [exists] = await file.exists();
    if (!exists) return [];
    const [buffer] = await file.download();
    bytes = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  } catch (error) {
    logger.warn('drive-heat: route download failed; skipping ride', {
      rideId,
      error: String(error),
    });
    return [];
  }

  const points = decodeRoute(bytes);
  if (!points || points.length < 2) return [];
  const trimmed = trimRouteEndpoints(points);
  if (trimmed.length === 0) return [];
  return routeCells(trimmed);
}

const SCHEDULE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_SCHEDULED,
  timeZone: 'Europe/Stockholm',
  // Route decoding + H3 binning over many drives is CPU/memory heavier than the
  // interaction aggregation; give it more headroom than the 256 MiB default.
  memory: '1GiB' as const,
  timeoutSeconds: 540,
};

/**
 * Daily rebuild of the anonymised drive heatmap (04:30 Europe/Stockholm — after
 * the partner-insights aggregate/cleanup at 03:00/04:00, off the busiest hours).
 */
export const aggregateDriveHeat_scheduled = onSchedule(
  { ...SCHEDULE_OPTS, schedule: '30 4 * * *' },
  withServerErrorReporting('partnerInsights.aggregateDriveHeat', async () => {
    await runDriveHeatAggregation(new Date());
  }),
);
