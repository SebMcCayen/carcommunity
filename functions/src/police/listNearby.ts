/**
 * police.listNearby — read of ACTIVE, unexpired user-reported POLICE pins near a
 * point, gated to active MEMBERS (contracts/functions/functions.json:
 * police.listNearby).
 *
 * Deployed via the `police` export group as `police-listNearby` (europe-west1).
 * Active-account-gated (requireActiveActor) to match the member-read `policeReports` rule
 * and the feature's least-privilege audience — unlike incidents.listNearby, which
 * is open to every signed-in user because that layer is deliberately shared with
 * all. Mirrors the crownHunt geo approach otherwise: the requested radius is
 * clamped, the covering grid cells are enumerated, and only those cells are read
 * via chunked Firestore `in` queries (`geoCell in [...]`) — never a
 * full-collection scan. Results are filtered to unexpired docs within the exact
 * Haversine radius (server-computed; a client distance is never trusted), capped.
 *
 * The Android map polls this on the same camera-idle cadence as the incident
 * layer to draw police markers and drive the proximity alert. Per-user
 * rate-limited (runaway guard) exactly like incidents.listNearby.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  FIRESTORE_IN_CHUNK,
  POLICE_ACTIVE_STATUS,
  POLICE_LIST_RATE_LIMIT_COLLECTION,
  chunk,
  clampRadiusMeters,
  geoCellsForRadius,
  isUnderPoliceListRateLimit,
  isWithinRadius,
  parseListNearbyInput,
  policeListRateLimitDocId,
  policeListRateLimitExpiry,
  readVoteCount,
  type PoliceReportSource,
  type PoliceReportView,
} from './police-core';
import { MAX_INSTANCES_HOT } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_HOT,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Upper bound on markers returned (map viewport does not need more). */
const MAX_RESULTS = 200;

export interface ListNearbyResponse {
  policeReports: PoliceReportView[];
}

export const listNearby = onCall(CALLABLE_OPTS, async (request): Promise<ListNearbyResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseListNearbyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  // Per-user rate limit immediately before the expensive geoCell reads, so a
  // throttled call (client hot loop / valid-token abuser) costs ~one counter get,
  // not up to 200 doc reads. On exceed we throw resource-exhausted; the Android
  // client keeps its previous markers, so a throttled tick just skips a refresh.
  await enforceListNearbyRateLimit(actor.uid);

  const { latitude, longitude } = parsed.input;
  const radius = clampRadiusMeters(parsed.input.radiusMeters);
  const now = Timestamp.now();

  const cells = geoCellsForRadius(latitude, longitude, radius);
  const cellChunks = chunk(cells, FIRESTORE_IN_CHUNK);

  // One `geoCell in [...]` query per chunk of covering cells, run in parallel.
  // Bound reads server-side to ACTIVE, still-unexpired docs. The `in` on geoCell
  // + `==` on status + range on expiresAt needs the composite index in
  // firebase/firestore.indexes.json. Bounded by the covering cells — never a
  // full-collection scan.
  const snapshots = await Promise.all(
    cellChunks.map((cellGroup) =>
      db
        .collection('policeReports')
        .where('geoCell', 'in', cellGroup)
        .where('status', '==', POLICE_ACTIVE_STATUS)
        .where('expiresAt', '>', now)
        .limit(MAX_RESULTS)
        .get(),
    ),
  );

  const seen = new Set<string>();
  const results: PoliceReportView[] = [];
  for (const snap of snapshots) {
    for (const doc of snap.docs) {
      if (seen.has(doc.id)) continue;
      const data = doc.data();
      if (data.status !== POLICE_ACTIVE_STATUS) continue;
      // Mirror the Firestore read rule (`expiresAt > request.time`): the Admin
      // SDK bypasses rules, so enforce the same intent — only a valid, still-
      // future Timestamp is kept.
      const expiresAt = data.expiresAt;
      if (!(expiresAt instanceof Timestamp) || expiresAt.toMillis() <= now.toMillis()) continue;
      const lat = data.latitude as number;
      const lng = data.longitude as number;
      if (typeof lat !== 'number' || typeof lng !== 'number') continue;
      if (!isWithinRadius(latitude, longitude, lat, lng, radius)) continue;
      seen.add(doc.id);
      results.push({
        id: doc.id,
        latitude: lat,
        longitude: lng,
        // Per-caller ownership only — the raw reporterUid is NEVER returned to a
        // client (privacy), it is compared here to the authenticated caller so the
        // reporter's own pin can suppress its proximity alert without leaking who
        // reported any other pin.
        mine: (data.reporterUid as string | null) === actor.uid,
        source: (data.source as PoliceReportSource) ?? 'manual',
        createdAt: tsToIso(data.createdAt),
        expiresAt: tsToIso(data.expiresAt),
        // Both verify tallies travel to the client on every pin so the tap sheet
        // shows "confirmed by N / disputed by N". A bulk read degrades a corrupt
        // counter to 0 (readVoteCount) rather than dropping the marker — the
        // opposite branch to the verify callable, which refuses to build on one.
        confirmationCount: readVoteCount(data.confirmationCount),
        disputeCount: readVoteCount(data.disputeCount),
      });
    }
  }

  results.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
  return { policeReports: results.slice(0, MAX_RESULTS) };
});

function tsToIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

/**
 * Fixed-window per-user rate limit for `police.listNearby` — same mechanism as
 * incidents.listNearby (deterministic counter doc read by id, bumped with
 * FieldValue.increment, TTL-reaped via `expireAt`), in its own collection.
 */
async function enforceListNearbyRateLimit(uid: string): Promise<void> {
  const nowMs = Date.now();
  const ref = db
    .collection(POLICE_LIST_RATE_LIMIT_COLLECTION)
    .doc(policeListRateLimitDocId(uid, nowMs));

  const snap = await ref.get();
  const currentCount = snap.get('count');
  if (!isUnderPoliceListRateLimit(typeof currentCount === 'number' ? currentCount : 0)) {
    throw new HttpsError(
      'resource-exhausted',
      'Too many nearby-police refreshes — please slow down and try again shortly.',
    );
  }

  await ref.set(
    {
      count: FieldValue.increment(1),
      uid,
      expireAt: Timestamp.fromDate(policeListRateLimitExpiry(nowMs)),
    },
    { merge: true },
  );
}

// One-time deploy step for the counter's TTL (spent windows self-delete):
//
//   gcloud firestore fields ttls update expireAt \
//     --collection-group=policeListRateLimits --enable-ttl
