/**
 * Partner DRIVE HEATMAP aggregation — the scheduled I/O job (Phase: partner
 * drive heat MVP).
 *
 * Once a day, over a rolling {@link DRIVE_HEAT_WINDOW_DAYS}-day window of
 * completed drives from CONSENTED users, this:
 *  1. lists `rides/{rideId}` in the window,
 *  2. checks explicit, versioned consent and account/deletion fences before
 *     reading a route; excludes rides started before the latest consent,
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
import { isRestricted, toUserAccessState } from '../shared/access';
import { PARTNER_STATS_CONSENT_VERSION, readPartnerStatsConsent } from './consent';
import {
  DriveHeatAccumulator,
  routeCells,
  trimRouteEndpoints,
  DRIVE_HEAT_H3_RESOLUTION,
  ENDPOINT_TRIM_METERS,
  MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
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
/** Fail without publishing a partial report when a bounded run is exhausted. */
const MAX_RIDES_PER_RUN = 5_000;
const MAX_CONTRIBUTORS_PER_RUN = 250;
const MAX_CONTRIBUTION_CELLS = 200_000;
/** A missed daily rebuild may retain a valid anonymous snapshot for at most 48h. */
export const DRIVE_HEAT_MAX_AGE_MS = 48 * 60 * 60 * 1000;

function consentRefs(uid: string) {
  return [
    db.collection('userPrivate').doc(uid),
    db.collection('users').doc(uid),
    db.collection('accountDeletionRequests').doc(uid),
    db.collection('accountPurgeProgress').doc(uid),
  ];
}

function permittedConsent(snaps: FirebaseFirestore.DocumentSnapshot[]) {
  const [priv, user, deletion, progress] = snaps;
  if (
    !user?.exists ||
    isRestricted(toUserAccessState(user.data())) ||
    ['pending', 'processed'].includes(deletion?.data()?.status) ||
    progress?.data()?.partnerErasureActive === true
  )
    return null;
  return readPartnerStatsConsent(priv?.data());
}

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

  // Identities exist only in this bounded invocation, never in published heat.
  // Keep per-ride contributions until the final transactional consent recheck.
  const contributions = new Map<string, { grantedAt: Timestamp; rides: string[][] }>();
  let contributionCells = 0;
  let ridesConsidered = 0;

  // Page through rides in the window by startedAt (single-field range → the
  // automatic index covers it; no composite index to hand-deploy).
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | null = null;
  for (;;) {
    let query = db
      .collection('rides')
      .where('startedAt', '>=', Timestamp.fromDate(cutoff))
      .where('startedAt', '<=', Timestamp.fromDate(now))
      .orderBy('startedAt', 'asc')
      .limit(RIDE_PAGE_SIZE);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    if (page.empty) break;

    for (const doc of page.docs) {
      ridesConsidered += 1;
      if (ridesConsidered > MAX_RIDES_PER_RUN) throw new Error('Drive heat ride budget exceeded.');
      const data = doc.data();
      const uid = data.userId as string | undefined;
      if (typeof uid !== 'string' || !uid) continue;
      const startedAt = data.startedAt;
      if (!(startedAt instanceof Timestamp)) continue;
      // Do not even download a saved personal route without current consent.
      const consent = permittedConsent(await db.getAll(...consentRefs(uid)));
      if (!consent || startedAt.toMillis() < consent.grantedAt.toMillis()) continue;
      const existing = contributions.get(uid);
      if (existing && !existing.grantedAt.isEqual(consent.grantedAt)) continue;
      if (!existing && contributions.size >= MAX_CONTRIBUTORS_PER_RUN)
        throw new Error('Drive heat contributor budget exceeded.');

      const cells = await cellsForRide(bucket, uid, doc.id);
      if (cells.length === 0) continue;
      contributionCells += cells.length;
      if (contributionCells > MAX_CONTRIBUTION_CELLS)
        throw new Error('Drive heat cell budget exceeded.');
      const entry = existing ?? { grantedAt: consent.grantedAt, rides: [] };
      entry.rides.push(cells);
      contributions.set(uid, entry);
    }

    if (page.size < RIDE_PAGE_SIZE) break;
    cursor = page.docs[page.size - 1]!;
  }

  const heatRef = db.collection(DRIVE_HEAT_DOC_PATH.collection).doc(DRIVE_HEAT_DOC_PATH.doc);
  const entries = [...contributions.entries()];
  const publication = await db.runTransaction(async (tx) => {
    // No Storage/Auth I/O in this retriable transaction. Every authority read
    // participates in conflict detection, including revoke/purge during publish.
    const refs = entries.flatMap(([uid]) => consentRefs(uid));
    const snapshots = refs.length ? await tx.getAll(...refs) : [];
    const previous = await tx.get(heatRef);
    const previousGeneratedAt = previous.data()?.generatedAt;
    if (
      previous.data()?.consentVersion === PARTNER_STATS_CONSENT_VERSION &&
      previousGeneratedAt instanceof Timestamp &&
      previousGeneratedAt.toMillis() <= Date.now() &&
      previousGeneratedAt.toMillis() > now.getTime()
    )
      throw new Error('A newer drive heat snapshot has already been published.');
    const accumulator = new DriveHeatAccumulator();
    let ridesContributing = 0;
    entries.forEach(([uid, entry], index) => {
      const consent = permittedConsent(snapshots.slice(index * 4, index * 4 + 4));
      if (!consent || !consent.grantedAt.isEqual(entry.grantedAt)) return;
      for (const cells of entry.rides) {
        accumulator.add(uid, cells);
        ridesContributing += 1;
      }
    });
    const cells = accumulator
      .finalize(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD)
      .slice(0, DRIVE_HEAT_MAX_CELLS);
    tx.set(heatRef, {
      // Published cells retain no identity or participant index. Withdrawal
      // stops new processing; a previously valid aggregate expires after 48h.
      consentVersion: PARTNER_STATS_CONSENT_VERSION,
      generatedAt: Timestamp.fromDate(now),
      windowDays: DRIVE_HEAT_WINDOW_DAYS,
      resolution: DRIVE_HEAT_H3_RESOLUTION,
      trimMeters: ENDPOINT_TRIM_METERS,
      threshold: MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD,
      cellCount: cells.length,
      cells: cells.map((c) => ({
        h3Index: c.h3Index,
        contributorCount: c.contributorCount,
        weight: c.weight,
      })),
    });
    return { ridesContributing, cellsWritten: cells.length };
  });

  logger.info('drive-heat aggregation complete', {
    ridesConsidered,
    ...publication,
    windowDays: DRIVE_HEAT_WINDOW_DAYS,
  });
  return { ridesConsidered, ...publication };
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
    // Bound compressed input independently of decoder's existing inflate cap.
    const maxBytes = 2 * 1024 * 1024;
    const [buffer] = await file.download({ start: 0, end: maxBytes, decompress: false });
    if (buffer.length > maxBytes) return [];
    bytes = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  } catch (error) {
    logger.warn('drive-heat: route download failed; skipping ride', {
      rideId,
      error: String(error),
    });
    return [];
  }

  const points = decodeRoute(bytes);
  if (!points || points.length < 2 || points.length > 20_000) return [];
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
  // DELIBERATE EXCEPTION to the CPU_SCHEDULED (0.5 vCPU) tier: this job decodes
  // 90 days of route files and H3-bins them, so it is genuinely CPU-bound and
  // stays at a full vCPU to avoid timeouts. concurrency stays 1 (scheduled, one
  // invocation per tick), which is also required to pin cpu without hitting the
  // gen2 "cpu < 1 needs concurrency 1" rule — here cpu is 1, so it is unaffected.
  cpu: 1,
  concurrency: 1,
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
