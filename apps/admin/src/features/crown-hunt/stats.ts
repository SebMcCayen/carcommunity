/**
 * Kronjakt STATISTICS + LEADERBOARD + SEASONS — admin read layer.
 *
 * The read half of the stats slice shipped in #710. Every aggregate below is
 * maintained EXCLUSIVELY by backend triggers + the scheduled season rollover
 * (Admin SDK); no client ever writes them. The admin dashboard READS them via
 * rules-gated Firestore SDK reads — the same direct-read pattern the points /
 * claims / spawn-cells views use — because these collections expose no callable:
 *
 *   - crownHuntSpawnStats/{scope}   admin-only totals (spawned/collected + by
 *     rarity). scope = 'alltime' | 'YYYY-MM'.
 *   - crownHuntCellStats/{cellKey}  admin-only per-cell heat-map counts.
 *   - crownHuntLeaderboardEntries   the public board ({scope}__{uid}); ranked by
 *     `where scope==X orderBy points desc, crownsCollected desc`.
 *   - crownHuntSeasons/{seasonId}   season metadata + finalized winners.
 *   - crownHuntUserStats/{uid}      per-member rich stats (owner + admin) — read
 *     only to resolve `seasonsWon` for a ranked member (best-effort).
 *
 * AUTHORITY / COVERAGE (per the shared contract): points / crowns / rank come
 * from the Kronpoäng ledger (both hand-placed and auto-spawned crowns count);
 * the rarity breakdown and the heat-map cover AUTO-SPAWNED crowns only, so
 * `collectedTotal - sum(collectedByRarity)` is the hand-placed remainder.
 *
 * NOT-STORED METRICS: `activePlayers7d/30d` and `collectionRate` from the
 * `CrownHuntAdminStats` contract are NOT persisted on the spawn-stats document
 * — the trigger writes only totals + rarity maps. `collectionRate` is DERIVED
 * here; active-player counts are surfaced only if the document actually carries
 * them (a future writer), else reported as `null` and rendered "—", never
 * fabricated.
 */

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit as fsLimit,
  orderBy,
  query,
  where,
  type DocumentData,
  type Timestamp,
} from 'firebase/firestore';

import type {
  CrownHuntCellStat,
  CrownHuntLeaderboardEntry,
  CrownHuntLeaderboardScope,
  CrownHuntRarity,
  CrownHuntRarityCounts,
  CrownHuntSeason,
  CrownHuntSeasonStatus,
  CrownHuntSeasonWinner,
} from '@carcommunity/shared/crown-hunt';
import {
  CROWN_HUNT_RARITIES,
  CROWN_HUNT_ALL_TIME_SCOPE,
} from '@carcommunity/shared/crown-hunt';

import { getAdminFirestore } from '../../lib/firestore';

export type {
  CrownHuntCellStat,
  CrownHuntLeaderboardEntry,
  CrownHuntSeason,
  CrownHuntSeasonWinner,
};

// Collection names (mirror functions/src/crownHunt/crown-hunt-stats-core.ts).
const SPAWN_STATS_COLLECTION = 'crownHuntSpawnStats';
const CELL_STATS_COLLECTION = 'crownHuntCellStats';
const LEADERBOARD_COLLECTION = 'crownHuntLeaderboardEntries';
const SEASONS_COLLECTION = 'crownHuntSeasons';
const USER_STATS_COLLECTION = 'crownHuntUserStats';

export const CROWN_STATS_CELL_DEGREES = 0.01;
export const ALL_TIME_SCOPE = CROWN_HUNT_ALL_TIME_SCOPE;

/** ISO id for the current Europe/Stockholm month, e.g. "2026-08". */
export function currentSeasonId(now: Date = new Date()): string {
  // The season boundary is Swedish local midnight; for an admin dashboard the
  // month component in Europe/Stockholm is sufficient and avoids a tz library.
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Europe/Stockholm',
    year: 'numeric',
    month: '2-digit',
  }).format(now);
  // sv-SE yields "YYYY-MM"; normalise any locale separator to a dash.
  return parts.replace(/[^\d]/g, '-').split('-').slice(0, 2).join('-');
}

// ---------------------------------------------------------------------------
// Pure coercers + mappers
// ---------------------------------------------------------------------------

function num(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (value instanceof Date) return value.toISOString();
  if (typeof value === 'string') return value;
  const ts = value as Timestamp;
  if (typeof ts?.toDate === 'function') return ts.toDate().toISOString();
  return null;
}

/** Read a sparse per-rarity map, coercing each present bucket to a number. */
export function toRarityCounts(value: unknown): CrownHuntRarityCounts {
  const out: CrownHuntRarityCounts = {};
  if (value && typeof value === 'object') {
    for (const rarity of CROWN_HUNT_RARITIES) {
      const bucket = (value as Record<string, unknown>)[rarity];
      if (typeof bucket === 'number' && Number.isFinite(bucket)) out[rarity] = bucket;
    }
  }
  return out;
}

/** Sum a rarity map across all four tiers (absent buckets count as 0). */
export function sumRarityCounts(counts: CrownHuntRarityCounts): number {
  return CROWN_HUNT_RARITIES.reduce((acc, r) => acc + (counts[r] ?? 0), 0);
}

/**
 * The admin spawn/collect dashboard totals for one scope. `collectionRate` is
 * DERIVED (collected/spawned, 0 when nothing spawned). `activePlayers*` are
 * `null` unless the document carries them (not written by the current trigger).
 */
export interface AdminSpawnStatsView {
  scope: string;
  spawnedTotal: number;
  collectedTotal: number;
  collectionRate: number;
  spawnedByRarity: CrownHuntRarityCounts;
  collectedByRarity: CrownHuntRarityCounts;
  /** Distinct-collector remainder from HAND-PLACED crowns (no rarity/cell). */
  handPlacedCollected: number;
  activePlayers7d: number | null;
  activePlayers30d: number | null;
  updatedAt: string | null;
}

/** Map a `crownHuntSpawnStats/{scope}` document to the dashboard view. */
export function toAdminSpawnStatsView(scope: string, data: DocumentData | undefined): AdminSpawnStatsView {
  const spawnedTotal = num(data?.spawnedTotal);
  const collectedTotal = num(data?.collectedTotal);
  const collectedByRarity = toRarityCounts(data?.collectedByRarity);
  return {
    scope,
    spawnedTotal,
    collectedTotal,
    collectionRate: spawnedTotal > 0 ? collectedTotal / spawnedTotal : 0,
    spawnedByRarity: toRarityCounts(data?.spawnedByRarity),
    collectedByRarity,
    handPlacedCollected: Math.max(0, collectedTotal - sumRarityCounts(collectedByRarity)),
    // Not written by the current trigger — surfaced only if a future writer adds
    // them; otherwise null so the UI shows "—" rather than a fabricated 0.
    activePlayers7d:
      typeof data?.activePlayers7d === 'number' ? (data.activePlayers7d as number) : null,
    activePlayers30d:
      typeof data?.activePlayers30d === 'number' ? (data.activePlayers30d as number) : null,
    updatedAt: toIso(data?.updatedAt),
  };
}

/** Map a `crownHuntCellStats/{cellKey}` document to the contract cell stat. */
export function toCellStat(id: string, data: DocumentData): CrownHuntCellStat {
  return {
    cellKey: id,
    spawned: num(data.spawned),
    collected: num(data.collected),
    spawnedByRarity: data.spawnedByRarity ? toRarityCounts(data.spawnedByRarity) : undefined,
    collectedByRarity: data.collectedByRarity ? toRarityCounts(data.collectedByRarity) : undefined,
    lastSpawnAt: toIso(data.lastSpawnAt),
    lastCollectAt: toIso(data.lastCollectAt),
  };
}

/** The centre `{lat, lon}` of a `latIdx_lonIdx` grid cell key (null if malformed). */
export function cellKeyCenter(cellKey: string): { lat: number; lon: number } | null {
  const m = /^(-?\d{1,6})_(-?\d{1,6})$/.exec(cellKey.trim());
  if (!m) return null;
  const latIdx = Number(m[1]);
  const lonIdx = Number(m[2]);
  if (!Number.isSafeInteger(latIdx) || !Number.isSafeInteger(lonIdx)) return null;
  return {
    lat: (latIdx + 0.5) * CROWN_STATS_CELL_DEGREES,
    lon: (lonIdx + 0.5) * CROWN_STATS_CELL_DEGREES,
  };
}

/** Map a `crownHuntSeasons/{seasonId}` document to the contract season. */
export function toSeason(id: string, data: DocumentData): CrownHuntSeason {
  const status = (data.status === 'ended' ? 'ended' : 'active') as CrownHuntSeasonStatus;
  const winners = Array.isArray(data.winners)
    ? (data.winners as DocumentData[]).map((w) => ({
        rank: num(w.rank),
        uid: String(w.uid ?? ''),
        displayName: String(w.displayName ?? ''),
        points: num(w.points),
        crownsCollected: num(w.crownsCollected),
      }))
    : undefined;
  return {
    seasonId: id,
    period: 'month',
    status,
    startAt: toIso(data.startAt) ?? '',
    endAt: toIso(data.endAt) ?? '',
    finalizedAt: toIso(data.finalizedAt),
    participantCount:
      typeof data.participantCount === 'number' ? (data.participantCount as number) : null,
    winners,
    topStandings: Array.isArray(data.topStandings)
      ? (data.topStandings as DocumentData[]).map((s) => ({
          rank: num(s.rank),
          uid: String(s.uid ?? ''),
          points: num(s.points),
          crownsCollected: num(s.crownsCollected),
        }))
      : undefined,
  };
}

// ---------------------------------------------------------------------------
// Leaderboard ranking (pure) — MUST agree with the server's rankLeaderboard
// ---------------------------------------------------------------------------

/** A raw leaderboard counter row before display resolution. */
export interface LeaderboardCounter {
  uid: string;
  points: number;
  crownsCollected: number;
}

/**
 * Rank raw counters by the canonical three-key order: points desc, then
 * crownsCollected desc, then uid ascending (stable final tiebreak). Zero-point,
 * zero-crown rows are dropped (a member who never collected is not "last", they
 * are simply off the board). Mirrors `rankLeaderboard` in the backend
 * stats-core so a client ordering agrees with the server's authoritative rank.
 */
export function rankLeaderboardCounters(
  counters: readonly LeaderboardCounter[],
): Array<LeaderboardCounter & { rank: number }> {
  return [...counters]
    .filter((e) => e.points > 0 || e.crownsCollected > 0)
    .sort(
      (a, b) =>
        b.points - a.points ||
        b.crownsCollected - a.crownsCollected ||
        (a.uid < b.uid ? -1 : a.uid > b.uid ? 1 : 0),
    )
    .map((e, index) => ({ ...e, rank: index + 1 }));
}

/** Map a `crownHuntLeaderboardEntries/{scope}__{uid}` document to a counter. */
export function toLeaderboardCounter(data: DocumentData): LeaderboardCounter {
  return {
    uid: String(data.uid ?? ''),
    points: num(data.points),
    crownsCollected: num(data.crownsCollected),
  };
}

// ---------------------------------------------------------------------------
// Firestore reads (admin rules-gated)
// ---------------------------------------------------------------------------

/** Read the spawn/collect totals for one scope ('alltime' or a season id). */
export async function adminGetSpawnStats(scope: string): Promise<AdminSpawnStatsView> {
  const snap = await getDoc(doc(getAdminFirestore(), SPAWN_STATS_COLLECTION, scope));
  return toAdminSpawnStatsView(scope, snap.exists() ? snap.data() : undefined);
}

/** Read the per-cell heat-map counts (most-recently active first, capped). */
export async function adminListCellStats(limitN = 500): Promise<CrownHuntCellStat[]> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), CELL_STATS_COLLECTION),
      orderBy('collected', 'desc'),
      fsLimit(limitN),
    ),
  );
  return snapshot.docs.map((d) => toCellStat(d.id, d.data()));
}

/**
 * Read the ranked leaderboard for one scope, resolving each member's display
 * name (from their public `users/{uid}` profile) and lifetime `seasonsWon`
 * (from `crownHuntUserStats/{uid}`, admin-readable). Name/champion resolution is
 * best-effort: an unresolved member falls back to a short uid and 0 wins, so a
 * missing profile never blocks the board.
 */
export async function adminListLeaderboard(
  scope: CrownHuntLeaderboardScope,
  seasonId: string | null,
  limitN = 20,
): Promise<CrownHuntLeaderboardEntry[]> {
  const db = getAdminFirestore();
  const scopeId = scope === 'alltime' ? ALL_TIME_SCOPE : seasonId ?? currentSeasonId();
  const snapshot = await getDocs(
    query(
      collection(db, LEADERBOARD_COLLECTION),
      where('scope', '==', scopeId),
      orderBy('points', 'desc'),
      orderBy('crownsCollected', 'desc'),
      fsLimit(limitN),
    ),
  );
  const ranked = rankLeaderboardCounters(snapshot.docs.map((d) => toLeaderboardCounter(d.data())));

  const resolved = await Promise.all(
    ranked.map(async (row) => {
      let displayName = row.uid.slice(0, 8);
      let seasonsWon = 0;
      try {
        const [profile, stats] = await Promise.all([
          getDoc(doc(db, 'users', row.uid)),
          getDoc(doc(db, USER_STATS_COLLECTION, row.uid)),
        ]);
        const name = (profile.data()?.displayName as string | undefined)?.trim();
        if (name) displayName = name;
        const won = stats.data()?.seasonsWon;
        if (typeof won === 'number' && Number.isFinite(won)) seasonsWon = won;
      } catch {
        // A single unresolved member must not fail the whole board.
      }
      return {
        rank: row.rank,
        uid: row.uid,
        displayName,
        points: row.points,
        crownsCollected: row.crownsCollected,
        seasonsWon,
      } satisfies CrownHuntLeaderboardEntry;
    }),
  );
  return resolved;
}

/** Read seasons, newest first (active season + finalized past seasons). */
export async function adminListSeasons(limitN = 12): Promise<CrownHuntSeason[]> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), SEASONS_COLLECTION),
      orderBy('startAt', 'desc'),
      fsLimit(limitN),
    ),
  );
  return snapshot.docs.map((d) => toSeason(d.id, d.data()));
}

/** Rarity tiers, ascending — re-exported for the dashboard rarity breakdown. */
export const RARITY_TIERS: readonly CrownHuntRarity[] = CROWN_HUNT_RARITIES;
