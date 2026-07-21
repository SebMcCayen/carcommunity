/**
 * live.listNearby — bounded read of ACTIVE, unexpired live sharers near a point,
 * so a standalone ("Single") live sharer is discoverable by other users nearby
 * (contracts/functions/functions.json: live.listNearby).
 *
 * Deployed via the `live` export group as `live-listNearby` (europe-west1).
 * Mirrors `incidents.listNearby` exactly: the requested radius is clamped, the
 * covering grid cells are enumerated, and only those cells are read via chunked
 * Firestore `geoCell in [...]` queries (never a full-collection scan). Results
 * are filtered to the exact server-computed Haversine radius (a client-supplied
 * distance is never trusted), then to the exclusions below, and capped.
 *
 * ## Discovery store
 * The queryable index is `liveSessions/{uid}` (see nearby-core.ts) — a
 * lightweight Firestore doc written by `live.updatePosition`. The high-frequency
 * position STREAM stays in RTDB (`liveLocation/{uid}/latest`); this callable
 * only returns the uids near the caller, and the client subscribes each uid's
 * existing per-uid RTDB `observeLatest` for the live updates.
 *
 * ## Exclusions (this IS a broadcast-to-strangers surface — handle deliberately)
 * - SELF: the caller's own session is never returned (they are the map puck).
 * - EXPIRED: `expiresAt > now`, enforced in the query AND re-checked in memory
 *   (the Admin SDK bypasses rules, so we mirror the read-rule intent here).
 * - BLOCKED (either direction): a candidate the caller blocked, OR who blocked
 *   the caller, is dropped — reusing the convoy block matrix
 *   (`resolvePeerBlockPairs`), which resolves both directions in reads that grow
 *   with the candidate count rather than with candidate×peer.
 * - SUSPENDED: enforced at WRITE time — `updatePosition` (the only writer of a
 *   discovery doc) requires a non-suspended actor (`requireActiveActor`), so a
 *   suspended user can neither create nor refresh a discovery doc and ages out
 *   within DISCOVERY_TTL of their last pre-suspension sample. The viewer side is
 *   also gated: the RTDB `liveLocation/{uid}/latest` read rule denies a
 *   suspended VIEWER, so even a returned uid yields no live marker for them.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldPath, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  FIRESTORE_IN_CHUNK,
  chunk,
  clampRadiusMeters,
  geoCellsForRadius,
  isWithinRadius,
} from '../incidents/incidents-core';
import {
  isBlockedAgainstAnyPeer,
  resolvePeerBlockPairs,
} from '../convoy/convoy-core';
import {
  LIVE_SESSION_ACTIVE_STATUS,
  parseListNearbyInput,
  type LiveNearbyView,
} from './nearby-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Upper bound on sharers returned (a map viewport does not need more). */
const MAX_RESULTS = 200;

export interface ListNearbyResponse {
  sessions: LiveNearbyView[];
}

/** Uids this blocker has blocked, out of `blockedUids` (<= FIRESTORE_IN_CHUNK). */
async function queryBlockedSubset(blockerUid: string, blockedUids: string[]): Promise<string[]> {
  const snap = await db
    .collection('userBlocks')
    .doc(blockerUid)
    .collection('blocked')
    .where(FieldPath.documentId(), 'in', blockedUids)
    .get();
  return snap.docs.map((doc) => doc.id);
}

export const listNearby = onCall(CALLABLE_OPTS, async (request): Promise<ListNearbyResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseListNearbyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { latitude, longitude } = parsed.input;
  const radius = clampRadiusMeters(parsed.input.radiusMeters);
  const now = Timestamp.now();

  const cells = geoCellsForRadius(latitude, longitude, radius);
  const cellChunks = chunk(cells, FIRESTORE_IN_CHUNK);

  // One `geoCell in [...]` query per chunk of covering cells, in parallel.
  // Bound reads to ACTIVE, still-unexpired docs — needs the composite index
  // liveSessions(geoCell, status, expiresAt) in firebase/firestore.indexes.json.
  //
  // The per-query `.limit` is DIVIDED across the chunks (ceil so it is >= 1),
  // so the TOTAL documents read across all chunks stays ~MAX_RESULTS rather than
  // MAX_RESULTS × chunkCount — a wide radius spanning several 30-cell chunks
  // therefore does not multiply the read cost (or the downstream block-matrix
  // work). The trade-off: if every sharer clusters in a single chunk we read at
  // most that chunk's share, but a nearby map viewport does not need more than
  // MAX_RESULTS markers in total, and the freshest are kept by the sort+cap
  // below. Bounded by the covering cells — never a full-collection scan.
  const perChunkLimit = Math.max(1, Math.ceil(MAX_RESULTS / cellChunks.length));
  const snapshots = await Promise.all(
    cellChunks.map((cellGroup) =>
      db
        .collection('liveSessions')
        .where('geoCell', 'in', cellGroup)
        .where('status', '==', LIVE_SESSION_ACTIVE_STATUS)
        .where('expiresAt', '>', now)
        .limit(perChunkLimit)
        .get(),
    ),
  );

  // First pass: dedupe, drop self, re-check expiry/coordinates, and apply the
  // exact Haversine radius. Collect the surviving candidates so the block
  // matrix is resolved ONCE over the whole set rather than per candidate.
  const seen = new Set<string>();
  const candidates: LiveNearbyView[] = [];
  for (const snap of snapshots) {
    for (const doc of snap.docs) {
      const uid = doc.id;
      if (uid === actor.uid) continue; // never return the caller's own session
      if (seen.has(uid)) continue;
      const data = doc.data();
      if (data.status !== LIVE_SESSION_ACTIVE_STATUS) continue;
      const expiresAt = data.expiresAt;
      if (!(expiresAt instanceof Timestamp) || expiresAt.toMillis() <= now.toMillis()) continue;
      const lat = data.latitude as number;
      const lng = data.longitude as number;
      if (typeof lat !== 'number' || typeof lng !== 'number') continue;
      if (!isWithinRadius(latitude, longitude, lat, lng, radius)) continue;
      seen.add(uid);
      candidates.push({
        uid,
        latitude: lat,
        longitude: lng,
        displayName: (data.displayName as string | null) ?? null,
        updatedAt: tsToIso(data.updatedAt),
        expiresAt: tsToIso(data.expiresAt),
      });
    }
  }

  // Each cell-group query is individually capped at MAX_RESULTS, so a wide
  // radius spanning several 30-cell chunks could yield more than MAX_RESULTS
  // candidates in total. Sort by freshness and cap to MAX_RESULTS HERE, BEFORE
  // resolving the block matrix, so the (read-bearing) block work is bounded to
  // the size of the response the caller can actually receive rather than to the
  // raw in-cell count. The final response is at most this many anyway (the
  // block filter only removes rows), so nothing visible is lost.
  candidates.sort((a, b) => (b.updatedAt ?? '').localeCompare(a.updatedAt ?? ''));
  const capped = candidates.slice(0, MAX_RESULTS);

  // Second pass: drop anyone in a block relationship with the caller in EITHER
  // direction. peers = [caller], candidates = the (capped) nearby uids — the
  // same matrix the convoy invite path uses, resolved in reads that grow with
  // the candidate count, not with candidate×peer.
  const candidateUids = capped.map((c) => c.uid);
  const blockPairs = await resolvePeerBlockPairs(candidateUids, [actor.uid], queryBlockedSubset);
  const results = capped.filter(
    (c) => !isBlockedAgainstAnyPeer(c.uid, [actor.uid], blockPairs),
  );

  return { sessions: results };
});

function tsToIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}
