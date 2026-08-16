/**
 * Kronjakt LIVE game-map data layer (admin).
 *
 * The read half of the admin live map: two real-time `onSnapshot` subscriptions
 * over the admin-readable game state —
 *
 *   - crownSpawns  (status == 'live')  → live auto-spawned crowns on the map
 *   - activePerks  (status == 'armed') → deployed traps (Spikmatta)
 *
 * Both collections are admin-readable directly (`isAdmin()` in firestore.rules:
 * crownSpawns `allow list: … || isAdmin()`, activePerks `allow read: … ||
 * isAdmin()`), so — exactly like the stats reads — no callable is involved.
 * Unlike the stats reads these are LIVE listeners, so the map reflects spawns
 * appearing/expiring and traps being armed/sprung without a manual refresh.
 *
 * A crown/trap doc keeps its status field even after it expires (TTL deletion is
 * eventual), so the pure mappers carry `expiresAtMs` and the map filters out
 * anything already past its expiry — the map shows only what is genuinely live
 * right now.
 */

import {
  collection,
  onSnapshot,
  query,
  where,
  type DocumentData,
  type Timestamp,
  type Unsubscribe,
} from 'firebase/firestore';

import { getAdminFirestore } from '../../lib/firestore';

const CROWN_SPAWNS_COLLECTION = 'crownSpawns';
const ACTIVE_PERKS_COLLECTION = 'activePerks';

/** A live auto-spawned crown, plotted as a gold marker on the live map. */
export interface LiveCrownSpawn {
  id: string;
  latitude: number;
  longitude: number;
  rarity: string | null;
  rewardPoints: number | null;
  /** Expiry as epoch millis, or null when absent/unparseable. */
  expiresAtMs: number | null;
}

/** A deployed trap (Spikmatta), plotted as a red marker on the live map. */
export interface LiveTrap {
  id: string;
  latitude: number;
  longitude: number;
  victimCount: number;
  expiresAtMs: number | null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Firestore Timestamp | Date | number → epoch millis (or null). */
function toMillis(value: unknown): number | null {
  if (value == null) return null;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (value instanceof Date) return value.getTime();
  const ts = value as Timestamp;
  if (typeof ts?.toMillis === 'function') return ts.toMillis();
  if (typeof ts?.toDate === 'function') return ts.toDate().getTime();
  return null;
}

/** True when the record has a finite coordinate AND is not already expired. */
export function isLiveNow(
  record: { latitude: number | null; longitude: number | null; expiresAtMs: number | null },
  nowMs: number,
): boolean {
  if (record.latitude === null || record.longitude === null) return false;
  // No expiry stamp → treat as live (don't hide a crown on a missing field).
  return record.expiresAtMs === null || record.expiresAtMs > nowMs;
}

/** Map a `crownSpawns/{id}` document to a live-crown record (coords may be null). */
export function toLiveCrownSpawn(id: string, data: DocumentData): LiveCrownSpawn & {
  latitude: number | null;
  longitude: number | null;
} {
  return {
    id,
    latitude: finiteNumber(data.latitude),
    longitude: finiteNumber(data.longitude),
    rarity: typeof data.rarity === 'string' ? data.rarity : null,
    rewardPoints: finiteNumber(data.rewardPoints),
    expiresAtMs: toMillis(data.expiresAt),
  } as LiveCrownSpawn & { latitude: number | null; longitude: number | null };
}

/** Map an `activePerks/{id}` trap document to a live-trap record. */
export function toLiveTrap(id: string, data: DocumentData): LiveTrap & {
  latitude: number | null;
  longitude: number | null;
} {
  return {
    id,
    // Trap docs store coordinates as `lat` / `lng` (see deployPerk.ts).
    latitude: finiteNumber(data.lat),
    longitude: finiteNumber(data.lng),
    victimCount: finiteNumber(data.victimCount) ?? 0,
    expiresAtMs: toMillis(data.expiresAt),
  } as LiveTrap & { latitude: number | null; longitude: number | null };
}

/**
 * Live-subscribe to the currently-live crowns. Emits the full non-expired set
 * on every change; returns the `onSnapshot` unsubscribe. Admin-gated by rules.
 */
export function subscribeLiveCrownSpawns(
  onData: (crowns: LiveCrownSpawn[]) => void,
  onError: (error: unknown) => void,
): Unsubscribe {
  const q = query(
    collection(getAdminFirestore(), CROWN_SPAWNS_COLLECTION),
    where('status', '==', 'live'),
  );
  return onSnapshot(
    q,
    (snap) => {
      const now = Date.now();
      const crowns = snap.docs
        .map((d) => toLiveCrownSpawn(d.id, d.data()))
        .filter((c) => isLiveNow(c, now)) as LiveCrownSpawn[];
      onData(crowns);
    },
    onError,
  );
}

/**
 * Live-subscribe to the currently-armed traps. Emits the full non-expired set
 * on every change; returns the `onSnapshot` unsubscribe. Admin-gated by rules.
 */
export function subscribeLiveTraps(
  onData: (traps: LiveTrap[]) => void,
  onError: (error: unknown) => void,
): Unsubscribe {
  const q = query(
    collection(getAdminFirestore(), ACTIVE_PERKS_COLLECTION),
    where('status', '==', 'armed'),
  );
  return onSnapshot(
    q,
    (snap) => {
      const now = Date.now();
      const traps = snap.docs
        .map((d) => toLiveTrap(d.id, d.data()))
        .filter((tr) => isLiveNow(tr, now)) as LiveTrap[];
      onData(traps);
    },
    onError,
  );
}
