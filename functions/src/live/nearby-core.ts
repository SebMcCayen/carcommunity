/**
 * Live-session NEARBY DISCOVERY core (pure logic) — the queryable geo index that
 * lets a standalone ("Single") live sharer be found by other users near them.
 *
 * ## Why a Firestore discovery doc alongside the RTDB position stream
 * Live positions live in Realtime Database (`liveLocation/{uid}/latest`), written
 * at high frequency by `live.updatePosition`. RTDB is the right store for that
 * stream — cheap realtime fan-out, `onDisconnect` presence, the existing
 * per-uid read rule + block mirror — but it is NOT geo-queryable: the parent
 * `liveLocation` node is `.read:false`, so a viewer can only read a marker whose
 * uid it ALREADY knows (a convoy/group roster). A solo sharer's uid therefore
 * never reaches a stranger nearby → they are invisible.
 *
 * So we ALSO write a lightweight, queryable Firestore doc per active sharer —
 * `liveSessions/{uid}` — carrying only what discovery needs: a coarse `geoCell`
 * grid key (SAME cell math as `incidents`), the last position, a denormalized
 * `displayName`, and an `expiresAt` TTL. `live.listNearby` then queries THIS
 * collection by geo-cell exactly the way `incidents.listNearby` queries
 * `incidents`, returns the uids near the caller, and the viewer subscribes each
 * uid's RTDB `observeLatest` for the live stream. RTDB stays the realtime
 * transport; Firestore is only the discovery index. This mirrors the
 * incidents-nearby pattern one-for-one and adds no second GPS store.
 *
 * The doc is written/refreshed on every `updatePosition` (that is the only place
 * a fresh coordinate + geoCell exists), reset on `startSession` (a restart must
 * not leave the previous session's stale position discoverable before the first
 * new sample), and DELETED on `stopSession`/`hideMeNow` and by the TTL sweep, so
 * a sharer who stops — or presses "hide me now" — vanishes from discovery at
 * once.
 *
 * Pure module — no Firebase Admin SDK imports. Geo maths are reused from
 * incidents-core (single source of truth for the grid-cell key + covering-cell
 * enumeration + Haversine radius test), so a discovery doc's `geoCell` and a
 * `listNearby` query cannot drift apart.
 */

import { z } from 'zod';
import { geoCellKey } from '../incidents/incidents-core';
import { LATEST_STALE_MINUTES } from './live-core';

export const LIVE_SESSION_ACTIVE_STATUS = 'active' as const;

/**
 * How long a discovery doc stays live after its last refresh, in ms.
 *
 * Set to the RTDB "silent-stale" window (`LATEST_STALE_MINUTES`), so a sharer's
 * discoverability and their RTDB marker age out on the SAME clock: the 5-minute
 * live sweep removes a silent marker at 15 minutes, and a discovery doc last
 * refreshed then is already `expiresAt <= now` and hidden by `listNearby`
 * (which filters `expiresAt > now`) before the same sweep deletes it. Every
 * `updatePosition` pushes it out again, so an actively-moving sharer never
 * expires mid-drive.
 */
export const DISCOVERY_TTL_MS = LATEST_STALE_MINUTES * 60 * 1000;

// ---------------------------------------------------------------------------
// listNearby input (mirrors incidents.listNearby)
// ---------------------------------------------------------------------------

const listNearbyInputSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    radiusMeters: z.number().positive().optional(),
  })
  .strict();

export type ListNearbyInput = z.infer<typeof listNearbyInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseListNearbyInput(data: unknown): ParseResult<ListNearbyInput> {
  const result = listNearbyInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: 'Expected { latitude, longitude, radiusMeters? }.' };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Discovery-doc builder (pure — the callable stamps the Timestamps)
// ---------------------------------------------------------------------------

/**
 * The non-Timestamp portion of a `liveSessions/{uid}` discovery doc. The
 * callable adds `updatedAt` and `expiresAt` (Firestore Timestamps) so this stays
 * a pure, testable object.
 */
export interface LiveDiscoveryFields {
  uid: string;
  geoCell: string;
  latitude: number;
  longitude: number;
  displayName: string | null;
  status: typeof LIVE_SESSION_ACTIVE_STATUS;
}

export function buildDiscoveryFields(params: {
  uid: string;
  latitude: number;
  longitude: number;
  displayName: string | null;
}): LiveDiscoveryFields {
  return {
    uid: params.uid,
    geoCell: geoCellKey(params.latitude, params.longitude),
    latitude: params.latitude,
    longitude: params.longitude,
    displayName: params.displayName,
    status: LIVE_SESSION_ACTIVE_STATUS,
  };
}

/**
 * The instant a discovery doc refreshed at `now` (for a session expiring at
 * `sessionExpiresAtIso`) should expire.
 *
 * The SOONER of the session's own end and `now + DISCOVERY_TTL_MS`: a 1h session
 * refreshed at minute 5 stays discoverable for the TTL window, never past the
 * session's own expiry. `updatePosition` requires an active (unexpired) session,
 * so in practice `now < sessionExpiresAt` always — the clamp is defence in depth
 * against a clock-skew edge, and against a malformed/absent session expiry
 * (which degrades to the plain TTL rather than a doc that never expires).
 */
export function discoveryExpiresAt(sessionExpiresAtIso: string | null | undefined, now: Date): Date {
  const ttlExpiry = now.getTime() + DISCOVERY_TTL_MS;
  const sessionExpiry = sessionExpiresAtIso ? Date.parse(sessionExpiresAtIso) : NaN;
  const bounded = Number.isFinite(sessionExpiry) ? Math.min(ttlExpiry, sessionExpiry) : ttlExpiry;
  return new Date(bounded);
}

// ---------------------------------------------------------------------------
// View returned to clients
// ---------------------------------------------------------------------------

/**
 * One nearby live sharer, as `live.listNearby` returns them. The viewer uses
 * [uid] to subscribe the EXISTING per-uid RTDB `observeLatest(uid)` stream (the
 * live source of truth); the coordinate + displayName here seed the initial
 * marker so it can be placed before the first RTDB frame arrives.
 */
export interface LiveNearbyView {
  uid: string;
  latitude: number;
  longitude: number;
  displayName: string | null;
  updatedAt: string | null;
  expiresAt: string | null;
}
