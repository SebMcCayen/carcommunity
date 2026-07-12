/**
 * incident.listNearby — read of ACTIVE, unexpired incidents near a point,
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
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  FIRESTORE_IN_CHUNK,
  INCIDENT_ACTIVE_STATUS,
  chunk,
  clampRadiusMeters,
  geoCellsForRadius,
  isWithinRadius,
  parseListNearbyInput,
  type IncidentType,
  type IncidentSource,
  type IncidentView,
} from './incidents-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
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
  await requireActiveActor(request);

  const parsed = parseListNearbyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { latitude, longitude } = parsed.input;
  const radius = clampRadiusMeters(parsed.input.radiusMeters);
  const now = Timestamp.now();

  const cells = geoCellsForRadius(latitude, longitude, radius);
  const cellChunks = chunk(cells, FIRESTORE_IN_CHUNK);

  // One `geoCell in [...]` query per chunk of covering cells, run in parallel.
  // A single-field `in` needs no composite index; status/expiry/exact-radius
  // are filtered in memory below. Bounded by the covering cells — never a
  // full-collection scan.
  const snapshots = await Promise.all(
    cellChunks.map((cellGroup) =>
      db.collection('incidents').where('geoCell', 'in', cellGroup).get(),
    ),
  );

  const seen = new Set<string>();
  const results: IncidentView[] = [];
  for (const snap of snapshots) {
    for (const doc of snap.docs) {
      if (seen.has(doc.id)) continue;
      const data = doc.data();
      if (data.status !== INCIDENT_ACTIVE_STATUS) continue;
      const expiresAt = data.expiresAt;
      if (expiresAt instanceof Timestamp && expiresAt.toMillis() <= now.toMillis()) continue;
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
        expiresAt: tsToIso(data.expiresAt),
      });
    }
  }

  results.sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
  return { incidents: results.slice(0, MAX_RESULTS) };
});

function tsToIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}
