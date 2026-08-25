/**
 * incidents.listNearby — read of ACTIVE, unexpired incidents near a point,
 * open to ANY active signed-in user (contracts/functions/functions.json:
 * incidents.listNearby).
 *
 * Deployed via the `incidents` export group as `incidents-listNearby`
 * (europe-west1). Mirrors the crownHunt geo approach: the requested radius is
 * clamped, the covering grid cells are enumerated, and only those cells are
 * read via chunked Firestore `in` queries (`geoCell in [...]`) — never a
 * full-collection scan. Results are then filtered to unexpired docs within the
 * exact Haversine radius (server-computed; a client-supplied distance is never
 * trusted) and capped.
 *
 * Any signed-in user reads incidents directly via security rules too (the
 * shared Waze-style map layer is visible to all users, not only members);
 * this callable exists so the map can fetch a bounded, distance-filtered batch
 * in one round-trip without every client running its own geo query.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  FIRESTORE_IN_CHUNK,
  INCIDENT_ACTIVE_STATUS,
  INCIDENT_LIST_RATE_LIMIT_COLLECTION,
  chunk,
  clampRadiusMeters,
  geoCellsForRadius,
  incidentListRateLimitDocId,
  incidentListRateLimitExpiry,
  isUnderIncidentListRateLimit,
  isWithinRadius,
  parseListNearbyInput,
  readClearedCount,
  readConfirmationCount,
  type IncidentType,
  type IncidentSource,
  type IncidentView,
} from './incidents-core';
import { MAX_INSTANCES_HOT } from '../shared/instanceLimits';
import {
  TRAFIKVERKET_FINGERPRINT_VERSION,
  TRAFIKVERKET_SYNC_METADATA_COLLECTION,
  TRAFIKVERKET_SYNC_METADATA_DOC,
} from './trafikverket-core';

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
  incidents: IncidentView[];
}

export const listNearby = onCall(CALLABLE_OPTS, async (request): Promise<ListNearbyResponse> => {
  const actor = await requireActiveActor(request);

  // Validate/parse BEFORE the rate limit. The actor gate above has already read
  // users/{uid}, so this is NOT a "no Firestore work" path — what it buys is
  // that a malformed call pays neither the counter get + write nor the geoCell
  // reads, and never burns the caller's rate-limit window on a bad payload.
  const parsed = parseListNearbyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  // Per-user rate limit immediately before the expensive geoCell reads below, so
  // a throttled call (client hot loop, or a valid-token abuser) costs ~one
  // counter get, not up to 200 doc reads. On exceed we throw resource-exhausted;
  // the Android client keeps its previous markers on a failed listNearby, so a
  // throttled user just misses one refresh tick — no crash, no blank map.
  await enforceListNearbyRateLimit(actor.uid);

  const { latitude, longitude } = parsed.input;
  const radius = clampRadiusMeters(parsed.input.radiusMeters);
  const now = Timestamp.now();

  // Imported docs have a stable far-future expiresAt so unchanged upstream
  // content needs no write. This ONE small metadata document is therefore the
  // freshness authority. Legacy rolling-TTL docs stay readable until their
  // first importer migration (or their existing expiry), preventing a rollout
  // gap if rules/functions deploy before the next scheduled sync succeeds.
  const importMetadata = await db
    .collection(TRAFIKVERKET_SYNC_METADATA_COLLECTION)
    .doc(TRAFIKVERKET_SYNC_METADATA_DOC)
    .get();
  const importFreshUntil = importMetadata.get('freshUntil');
  const trafikverketImportFresh =
    importFreshUntil instanceof Timestamp && importFreshUntil.toMillis() > now.toMillis();

  const cells = geoCellsForRadius(latitude, longitude, radius);
  const cellChunks = chunk(cells, FIRESTORE_IN_CHUNK);

  // One `geoCell in [...]` query per chunk of covering cells, run in parallel.
  // Bound reads server-side to ACTIVE, still-unexpired docs so we do not pay for
  // expired/inactive rows in-cell; the exact Haversine radius and a Timestamp-
  // type guard for expiresAt are still applied in memory below (defense in
  // depth). The `in` on geoCell + `==` on status + range on expiresAt needs the
  // composite index in firebase/firestore.indexes.json. Bounded by the covering
  // cells — never a full-collection scan.
  const snapshots = await Promise.all(
    cellChunks.map((cellGroup) =>
      db
        .collection('incidents')
        .where('geoCell', 'in', cellGroup)
        .where('status', '==', INCIDENT_ACTIVE_STATUS)
        .where('expiresAt', '>', now)
        // Bound worst-case reads per chunk: the whole response is capped to
        // MAX_RESULTS, so no single cell-group ever needs to return more.
        .limit(MAX_RESULTS)
        .get(),
    ),
  );

  const seen = new Set<string>();
  const results: IncidentView[] = [];
  for (const snap of snapshots) {
    for (const doc of snap.docs) {
      if (seen.has(doc.id)) continue;
      const data = doc.data();
      if (data.status !== INCIDENT_ACTIVE_STATUS) continue;
      if (
        data.source === 'trafikverket' &&
        data.importFingerprintVersion === TRAFIKVERKET_FINGERPRINT_VERSION &&
        !trafikverketImportFresh
      ) {
        continue;
      }
      // Mirror the Firestore read rule (`expiresAt > request.time`), which
      // denies a missing/non-Timestamp expiresAt. The Admin SDK bypasses
      // rules, so enforce the same intent here: only a valid, still-future
      // Timestamp is kept — a doc with no expiresAt or a malformed type is
      // excluded rather than leaked.
      const expiresAt = data.expiresAt;
      if (!(expiresAt instanceof Timestamp) || expiresAt.toMillis() <= now.toMillis()) continue;
      const lat = data.latitude as number;
      const lng = data.longitude as number;
      if (typeof lat !== 'number' || typeof lng !== 'number') continue;
      if (!isWithinRadius(latitude, longitude, lat, lng, radius)) continue;
      seen.add(doc.id);
      results.push({
        id: doc.id,
        type: data.type as IncidentType,
        latitude: lat,
        longitude: lng,
        source: (data.source as IncidentSource) ?? 'user',
        reporterUid: (data.reporterUid as string | null) ?? null,
        note: (data.note as string | null) ?? null,
        createdAt: tsToIso(data.createdAt),
        // The Trafikverket importer's authoritative "posted at" (upstream's
        // original time). Absent on member reports and on imports where upstream
        // sent no usable time — null then, and the client hides the age line for
        // Trafikverket rows rather than showing our sync time.
        postedAt: tsToIso(data.postedAt),
        expiresAt: tsToIso(data.expiresAt),
        // Absent until the first confirmation writes it. Corrupt (NaN, negative,
        // fractional) degrades to 0 for this one marker rather than failing the
        // batch — see readConfirmationCount.
        confirmationCount: readConfirmationCount(data.confirmationCount),
        // Both clear-vote fields travel with the marker so the client can draw
        // the faded "reported gone by N" state and show BOTH signals side by
        // side. Absent until the first clear vote; same degrade-to-0 posture.
        clearedCount: readClearedCount(data.clearedCount),
        // Only a literal `true` fades a marker. A missing or non-boolean value
        // reads as "not reported gone", so a malformed document can never dim a
        // live hazard on everyone's map — of the two ways to be wrong, making a
        // real incident look stale is the one that gets someone hurt.
        reportedCleared: data.reportedCleared === true,
      });
    }
  }

  results.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
  return { incidents: results.slice(0, MAX_RESULTS) };
});

function tsToIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

/**
 * Fixed-window per-user rate limit for `incidents.listNearby`.
 *
 * Reads the deterministic counter doc for (uid, current minute) BY ID — no
 * query, no composite index — and throws `resource-exhausted` once the uid has
 * already made INCIDENT_LIST_RATE_LIMIT_MAX calls in this window. Otherwise it
 * bumps the counter with FieldValue.increment(1) (a commutative, contention-free
 * server op — no transaction) and stamps `expireAt` so a Firestore TTL policy
 * reaps the spent window. A rejected call performs the single get and NO write.
 *
 * Deliberately not a transactional windowed count() (the shape used by the
 * lower-frequency feedback / error / moderation limiters): on this hot read path
 * that would add a composite index and hot-single-doc transaction contention.
 * The pure admit/reject decision lives in incidents-core
 * (isUnderIncidentListRateLimit) and is unit-tested there. Under concurrency a
 * handful of calls may read the same pre-increment count and slip through at the
 * window boundary; that is fine — the goal is to stop a runaway (hundreds–
 * thousands/min), not to be exact at the 60/61 boundary.
 */
async function enforceListNearbyRateLimit(uid: string): Promise<void> {
  const nowMs = Date.now();
  const ref = db
    .collection(INCIDENT_LIST_RATE_LIMIT_COLLECTION)
    .doc(incidentListRateLimitDocId(uid, nowMs));

  const snap = await ref.get();
  const currentCount = snap.get('count');
  if (!isUnderIncidentListRateLimit(typeof currentCount === 'number' ? currentCount : 0)) {
    throw new HttpsError(
      'resource-exhausted',
      'Too many nearby-incident refreshes — please slow down and try again shortly.',
    );
  }

  await ref.set(
    {
      count: FieldValue.increment(1),
      uid,
      // Firestore TTL policy on `expireAt` reaps spent windows (deploy note
      // below). Idempotent within a window: every write in the same minute sets
      // the same instant, so the merge is stable.
      expireAt: Timestamp.fromDate(incidentListRateLimitExpiry(nowMs)),
    },
    { merge: true },
  );
}

// One-time deploy step for the counter's TTL (spent windows self-delete so the
// incidentListRateLimits collection never accumulates):
//
//   gcloud firestore fields ttls update expireAt \
//     --collection-group=incidentListRateLimits --enable-ttl
//
// The collection is backend-only: written here via the Admin SDK and denied to
// all clients by firebase/firestore.rules. It needs no composite index (the
// counter is read by document id) and no client-readable rule.
