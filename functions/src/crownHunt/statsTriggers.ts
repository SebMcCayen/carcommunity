/**
 * Kronjakt STATS instrumentation — Firestore triggers.
 *
 * Collection increments the leaderboard and stat counters here, from a TRIGGER,
 * not from an edit to the collection callables. That is deliberate and keeps
 * this slice merge-trivial against the spawn-engine work happening in parallel:
 * `claimSpawn.ts` and `submitClaim.ts` are left untouched, and everything hangs
 * off documents the backend already writes.
 *
 * TWO SOURCES, split by what each one actually knows:
 *
 *  1. THE LEDGER (`onCrownLedgerEntryForStats`). Both crown paths — hand-placed
 *     `crownHunt.submitClaim` and auto-spawned `crownHunt.claimSpawn` — credit
 *     the Kronpoäng ledger with `source: 'crown_hunt'`. That single write is the
 *     authority for the LEADERBOARD (points + crowns, all-time AND per-season)
 *     and the personal daily-collection streak. It has the uid, the amount and
 *     the time, which is all the leaderboard needs — and it covers BOTH crown
 *     kinds without caring which one fired.
 *
 *  2. THE CROWN DOCUMENT (`onCrownSpawnStatsWritten`). A rarity and a grid cell
 *     exist ONLY on a `crownSpawns` document, so the rarity breakdown, the
 *     "rarest crown found" and the spawn/collect HEAT-MAP are driven off it: a
 *     spawn is the create edge, a collection is the transition to `claimed`.
 *     This covers auto-spawned crowns only — hand-placed points have neither a
 *     rarity nor a cell — so `crownsCollected - sum(byRarity)` is the
 *     hand-placed remainder, by design.
 *
 * The two never both touch the same field: the ledger trigger owns points /
 * crowns / streak, the crown trigger owns rarity / rarest / heat-map, so the
 * shared `crownHuntUserStats/{uid}` document is written by both with disjoint
 * merges and nothing double-counts.
 *
 * EXACTLY ONCE under at-least-once trigger delivery: every increment is guarded
 * by a create-if-absent fold marker inside the same transaction, exactly like
 * the pre-existing crown fold in points/economyTriggers.ts. A redelivery finds
 * the marker and does nothing. All handlers are best-effort — a stats side
 * effect must never fail (or retry-storm) the collection that already
 * succeeded — so they swallow and log.
 */

import { createHash } from 'node:crypto';
import { onDocumentCreated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import {
  ALL_TIME_SCOPE,
  CROWN_CELL_STATS_COLLECTION,
  CROWN_LEADERBOARD_COLLECTION,
  CROWN_SPAWN_STATS_COLLECTION,
  CROWN_STATS_RARITIES,
  CROWN_STAT_LEDGER_FOLDS_COLLECTION,
  CROWN_STAT_SPAWN_FOLDS_COLLECTION,
  CROWN_USER_STATS_COLLECTION,
  advanceCollectionStreak,
  isCrownStatsRarity,
  ledgerStatFoldId,
  rarerThan,
  leaderboardEntryDocId,
  readCollectionStreak,
  seasonIdForInstant,
  spawnStatFoldId,
  stockholmDayKey,
  type CrownStatsRarity,
} from './crown-hunt-stats-core';
import {
  CROWN_PERK_STATS_COLLECTION,
  CROWN_PERK_STAT_FOLDS_COLLECTION,
  isPerkId,
  perkStatFoldId,
  type PerkId,
  type PerkStatSource,
} from './perk-stats-core';
import { PERK_IDS } from './perks-core';
import { MAX_INSTANCES_TRIGGER, CPU_TRIGGER } from '../shared/instanceLimits';

const TRIGGER_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_TRIGGER,
  cpu: CPU_TRIGGER,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
};

// ---------------------------------------------------------------------------
// Shared refs
// ---------------------------------------------------------------------------

function leaderboardEntryRef(scope: string, uid: string): FirebaseFirestore.DocumentReference {
  return db.collection(CROWN_LEADERBOARD_COLLECTION).doc(leaderboardEntryDocId(scope, uid));
}

function userStatsRef(uid: string): FirebaseFirestore.DocumentReference {
  return db.collection(CROWN_USER_STATS_COLLECTION).doc(uid);
}

/** Reads a `Timestamp` field, falling back to the trigger delivery time. */
function readInstant(value: unknown, fallback: Date): Date {
  return value instanceof Timestamp ? value.toDate() : fallback;
}

// ---------------------------------------------------------------------------
// 1. Ledger → leaderboard + streak
// ---------------------------------------------------------------------------

/**
 * Folds a Kronjakt Kronpoäng award into the leaderboard (all-time + season) and
 * the personal daily-collection streak. Fires for BOTH crown paths, since both
 * write `source: 'crown_hunt'`.
 *
 * Only `earn` entries count — a `reversal` (admin correction) does not strip a
 * crown off the board, the same conservative asymmetry the daily-cap fold uses.
 */
export const onCrownLedgerEntryForStats = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'pointsLedger/{uid}/entries/{entryId}' },
  async (event) => {
    const data = event.data?.data();

    // PERK-SHOP PURCHASE branch (admin-stats PR-A). A perk purchase writes a
    // `perk_shop` ledger entry on this SAME path — so the perk aggregate is fed
    // from a branch HERE rather than a second trigger registered on
    // pointsLedger/{uid}/entries (a second onDocumentCreated on one path would
    // double-register). The crown_hunt leaderboard fold below is untouched.
    if (data?.source === 'perk_shop') {
      const perkId = data.relatedEntityId;
      if (data.transactionType === 'spend' && data.relatedEntityType === 'perk' && isPerkId(perkId)) {
        const uid = event.params.uid;
        const entryId = event.params.entryId;
        const purchasedAt = readInstant(
          data.createdAt,
          event.data?.createTime?.toDate() ?? new Date(),
        );
        await foldPerkStat({
          source: 'purchase',
          sourceDocId: `${uid}__${entryId}`,
          instant: purchasedAt,
          perkField: { base: 'purchasedByPerk', perkId },
          foldExtra: { uid, entryId, perkId },
        });
      }
      return;
    }

    if (!data || data.source !== 'crown_hunt' || data.transactionType !== 'earn') {
      return;
    }
    const amount = data.amount;
    if (typeof amount !== 'number' || !Number.isSafeInteger(amount) || amount <= 0) {
      return;
    }
    const uid = event.params.uid;
    const entryId = event.params.entryId;
    const collectedAt = readInstant(data.createdAt, event.data?.createTime?.toDate() ?? new Date());
    const seasonId = seasonIdForInstant(collectedAt);
    const dayKey = stockholmDayKey(collectedAt);

    const foldRef = db
      .collection(CROWN_STAT_LEDGER_FOLDS_COLLECTION)
      .doc(ledgerStatFoldId(uid, entryId));
    const statsRef = userStatsRef(uid);

    try {
      await db.runTransaction(async (tx) => {
        const [foldSnap, statsSnap] = await Promise.all([tx.get(foldRef), tx.get(statsRef)]);
        if (foldSnap.exists) {
          return;
        }
        tx.create(foldRef, {
          uid,
          entryId,
          amount,
          seasonId,
          day: dayKey,
          createdAt: FieldValue.serverTimestamp(),
        });

        // Leaderboard counters (blind increments — never read, so two crown
        // collections cannot race the counter). One document per (scope, uid);
        // the `scope` field is what the ranked read and the rank count filter on.
        for (const scope of [ALL_TIME_SCOPE, seasonId]) {
          tx.set(
            leaderboardEntryRef(scope, uid),
            {
              scope,
              uid,
              points: FieldValue.increment(amount),
              crownsCollected: FieldValue.increment(1),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true },
          );
        }

        // Personal streak + last-collection time (read-modify-write).
        const advanced = advanceCollectionStreak(readCollectionStreak(statsSnap.data()), dayKey);
        const previousLast = statsSnap.data()?.lastCollectionAt;
        const keepExistingLast =
          previousLast instanceof Timestamp && previousLast.toMillis() >= collectedAt.getTime();
        const patch: Record<string, unknown> = {
          uid,
          // Contract-completeness: initialise the fields the crownSpawns trigger
          // owns so a member who ONLY collects hand-placed crowns (ledger-only
          // path — no crownSpawns document ever fires for them) still gets a
          // stats doc with `byRarity` and `seasonsWon` present. Both are no-ops
          // (increment 0) on any value the other writers set.
          byRarity: rarityInitZeros(),
          seasonsWon: FieldValue.increment(0),
          updatedAt: FieldValue.serverTimestamp(),
        };
        if (!keepExistingLast) {
          patch.lastCollectionAt = Timestamp.fromDate(collectedAt);
        }
        if (advanced.changed) {
          patch.collectionStreakCurrent = advanced.state.current;
          patch.collectionStreakBest = advanced.state.best;
          patch.lastCollectionDayKey = advanced.state.lastDayKey;
        }
        tx.set(statsRef, patch, { merge: true });
      });
    } catch (error) {
      logger.warn('Crown leaderboard fold failed', { uid, entryId, error: String(error) });
    }
  },
);

// ---------------------------------------------------------------------------
// 2. crownSpawns → rarity + heat-map (admin) + personal rarity
// ---------------------------------------------------------------------------

function spawnStatsRef(scope: string): FirebaseFirestore.DocumentReference {
  return db.collection(CROWN_SPAWN_STATS_COLLECTION).doc(scope);
}

function cellStatsRef(cellKey: string): FirebaseFirestore.DocumentReference {
  return db.collection(CROWN_CELL_STATS_COLLECTION).doc(cellKey);
}

function rarityField(base: string, rarity: CrownStatsRarity | null): Record<string, unknown> {
  return rarity ? { [base]: { [rarity]: FieldValue.increment(1) } } : {};
}

/**
 * All four rarity buckets as `increment(0)` — makes a `byRarity` map a PRESENT,
 * full four-key map without changing any count. Used so `crownHuntUserStats`
 * always satisfies the read contract (`byRarity` present) whichever trigger
 * writes the document first; `increment(0)` is a no-op on any bucket that
 * already has a real count.
 */
function rarityInitZeros(): Record<string, FirebaseFirestore.FieldValue> {
  const out: Record<string, FirebaseFirestore.FieldValue> = {};
  for (const r of CROWN_STATS_RARITIES) {
    out[r] = FieldValue.increment(0);
  }
  return out;
}

/**
 * A spawn is the create edge of a `crownSpawns` document (`status: 'live'`); a
 * collection is its transition to `status: 'claimed'` (set by claimSpawn inside
 * the award transaction). A delete (the TTL sweeper) is ignored — a crown
 * ageing off the map is not a stat event.
 */
export const onCrownSpawnStatsWritten = onDocumentWritten(
  { ...TRIGGER_OPTS, document: 'crownSpawns/{spawnId}' },
  async (event) => {
    const spawnId = event.params.spawnId;
    const beforeExists = event.data?.before.exists === true;
    const after = event.data?.after.data();
    if (!after) {
      return; // deletion
    }
    const before = event.data?.before.data();
    const rarity: CrownStatsRarity | null = isCrownStatsRarity(after.rarity) ? after.rarity : null;
    const cellKey = typeof after.cellKey === 'string' && after.cellKey.length > 0 ? after.cellKey : null;
    const now = event.data?.after.updateTime?.toDate() ?? new Date();

    // ----- SPAWN edge -----
    if (!beforeExists && after.status === 'live') {
      const spawnedAt = readInstant(after.createdAt, now);
      const seasonId = seasonIdForInstant(spawnedAt);
      const foldRef = db
        .collection(CROWN_STAT_SPAWN_FOLDS_COLLECTION)
        .doc(spawnStatFoldId(spawnId, 'spawn'));
      try {
        await db.runTransaction(async (tx) => {
          if ((await tx.get(foldRef)).exists) {
            return;
          }
          tx.create(foldRef, { spawnId, phase: 'spawn', seasonId, createdAt: FieldValue.serverTimestamp() });
          for (const ref of [spawnStatsRef(ALL_TIME_SCOPE), spawnStatsRef(seasonId)]) {
            tx.set(
              ref,
              {
                scope: ref.id,
                spawnedTotal: FieldValue.increment(1),
                ...rarityField('spawnedByRarity', rarity),
                updatedAt: FieldValue.serverTimestamp(),
              },
              { merge: true },
            );
          }
          if (cellKey) {
            tx.set(
              cellStatsRef(cellKey),
              {
                cellKey,
                spawned: FieldValue.increment(1),
                ...rarityField('spawnedByRarity', rarity),
                lastSpawnAt: Timestamp.fromDate(spawnedAt),
                updatedAt: FieldValue.serverTimestamp(),
              },
              { merge: true },
            );
          }
        });
      } catch (error) {
        logger.warn('Crown spawn stat fold failed', { spawnId, error: String(error) });
      }
      return;
    }

    // ----- COLLECT edge -----
    const becameClaimed = before?.status !== 'claimed' && after.status === 'claimed';
    if (!becameClaimed) {
      return;
    }
    const claimedByUid = typeof after.claimedByUid === 'string' ? after.claimedByUid : null;
    const collectedAt = readInstant(after.claimedAt, now);
    const seasonId = seasonIdForInstant(collectedAt);
    const foldRef = db
      .collection(CROWN_STAT_SPAWN_FOLDS_COLLECTION)
      .doc(spawnStatFoldId(spawnId, 'collect'));
    const statsRef = claimedByUid ? userStatsRef(claimedByUid) : null;

    try {
      await db.runTransaction(async (tx) => {
        // Read the fold marker and (for rarest maintenance) the collector's
        // stats before any write, per Firestore's read-before-write rule.
        const [foldSnap, statsSnap] = await Promise.all([
          tx.get(foldRef),
          statsRef ? tx.get(statsRef) : Promise.resolve(null),
        ]);
        if (foldSnap.exists) {
          return;
        }
        tx.create(foldRef, {
          spawnId,
          phase: 'collect',
          seasonId,
          uid: claimedByUid,
          createdAt: FieldValue.serverTimestamp(),
        });

        for (const ref of [spawnStatsRef(ALL_TIME_SCOPE), spawnStatsRef(seasonId)]) {
          tx.set(
            ref,
            {
              scope: ref.id,
              collectedTotal: FieldValue.increment(1),
              ...rarityField('collectedByRarity', rarity),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true },
          );
        }
        if (cellKey) {
          tx.set(
            cellStatsRef(cellKey),
            {
              cellKey,
              collected: FieldValue.increment(1),
              ...rarityField('collectedByRarity', rarity),
              lastCollectAt: Timestamp.fromDate(collectedAt),
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true },
          );
        }
        if (statsRef && rarity) {
          const current = statsSnap?.data()?.rarestRarity;
          const incumbent: CrownStatsRarity | null = isCrownStatsRarity(current) ? current : null;
          const patch: Record<string, unknown> = {
            uid: claimedByUid,
            // Full four-key map (target +1, the rest +0) plus a seasonsWon
            // initialiser, so if this trigger is the FIRST writer of the stats
            // doc (trigger ordering is not guaranteed) it is already
            // contract-complete rather than missing seasonsWon until the ledger
            // trigger catches up.
            byRarity: { ...rarityInitZeros(), [rarity]: FieldValue.increment(1) },
            seasonsWon: FieldValue.increment(0),
            updatedAt: FieldValue.serverTimestamp(),
          };
          if (rarerThan(rarity, incumbent)) {
            patch.rarestRarity = rarity;
            patch.rarestAt = Timestamp.fromDate(collectedAt);
          }
          tx.set(statsRef, patch, { merge: true });
        }
      });
    } catch (error) {
      logger.warn('Crown collect stat fold failed', { spawnId, error: String(error) });
    }
  },
);

// ---------------------------------------------------------------------------
// 3. Perks → admin perk-usage aggregate (crownHuntPerkStats)  [admin-stats PR-A]
//
// A THIRD, admin-only aggregate, fed by the perk documents the shop/PvP layer
// already writes. It mirrors the spawn-stats trigger exactly: one small
// fixed-key document per scope (all-time + season), blind `increment`s (never
// read, so two events cannot race the counter), and a create-if-absent fold
// marker per source EVENT guarding BOTH scope increments in one transaction.
// Best-effort: a stats side effect must never fail the perk action that already
// succeeded, so every handler swallows and logs.
//
// THREE sources:
//   - perkDeploys/{deployId}  → usedByPerk[perkId]  (every deploy/activation)
//   - perkDrains/{drainId}    → trapTriggers        (each trap trigger; the source
//                                has a 30-day TTL, so it is tallied as it happens
//                                — it cannot be backfilled once reaped)
//   - the perk_shop ledger branch above → purchasedByPerk[perkId]
// Perks are not season-stamped, so the season is derived from each event's
// `createdAt` via the shared `seasonIdForInstant`.
// ---------------------------------------------------------------------------

function perkStatsRef(scope: string): FirebaseFirestore.DocumentReference {
  return db.collection(CROWN_PERK_STATS_COLLECTION).doc(scope);
}

/**
 * All perk buckets as `increment(0)` — makes a `*ByPerk` map a PRESENT, full
 * fixed-key map without changing any count, so `crownHuntPerkStats/{scope}` is
 * always contract-complete whichever source writes it first. `increment(0)` is a
 * no-op on any bucket that already has a real count.
 */
function perkCountsInit(): Record<string, FirebaseFirestore.FieldValue> {
  const out: Record<string, FirebaseFirestore.FieldValue> = {};
  for (const id of PERK_IDS) {
    out[id] = FieldValue.increment(0);
  }
  return out;
}

/**
 * Folds ONE perk event into `crownHuntPerkStats` on both the all-time and season
 * scopes, exactly once, under a single fold marker. `perkField` bumps a per-perk
 * map bucket (+1); `scalarField` bumps a scalar (+1). Every write initialises
 * the full document shape so a scope's doc is always complete.
 */
async function foldPerkStat(args: {
  source: PerkStatSource;
  sourceDocId: string;
  instant: Date;
  perkField?: { base: 'usedByPerk' | 'purchasedByPerk'; perkId: PerkId };
  scalarField?: 'trapTriggers';
  foldExtra: Record<string, unknown>;
}): Promise<void> {
  const { source, sourceDocId, instant, perkField, scalarField, foldExtra } = args;
  const seasonId = seasonIdForInstant(instant);
  const foldRef = db
    .collection(CROWN_PERK_STAT_FOLDS_COLLECTION)
    .doc(perkStatFoldId(source, sourceDocId));

  try {
    await db.runTransaction(async (tx) => {
      if ((await tx.get(foldRef)).exists) {
        return;
      }
      tx.create(foldRef, {
        source,
        sourceDocId,
        seasonId,
        createdAt: FieldValue.serverTimestamp(),
        ...foldExtra,
      });
      for (const ref of [perkStatsRef(ALL_TIME_SCOPE), perkStatsRef(seasonId)]) {
        const patch: Record<string, unknown> = {
          scope: ref.id,
          // Full fixed-key shape every time (all no-ops unless overwritten below).
          usedByPerk: perkCountsInit(),
          purchasedByPerk: perkCountsInit(),
          trapTriggers: FieldValue.increment(0),
          updatedAt: FieldValue.serverTimestamp(),
        };
        if (perkField) {
          patch[perkField.base] = {
            ...perkCountsInit(),
            [perkField.perkId]: FieldValue.increment(1),
          };
        }
        if (scalarField) {
          patch[scalarField] = FieldValue.increment(1);
        }
        tx.set(ref, patch, { merge: true });
      }
    });
  } catch (error) {
    // Log a HASHED correlation id, never the raw sourceDocId: for a purchase the
    // id is `${uid}__${entryId}` and logging it raw would leak a user identifier
    // on this best-effort side path. A short SHA-256 prefix keeps a fold failure
    // traceable to its source event without exposing the uid.
    logger.warn('Perk stat fold failed', {
      source,
      ref: createHash('sha256').update(sourceDocId).digest('hex').slice(0, 12),
      error: String(error),
    });
  }
}

/**
 * A perk DEPLOY/ACTIVATION — the create edge of `perkDeploys/{deployId}`, written
 * once per deploy of any kind (trap drop, shield raise, boost arm). Counts toward
 * `usedByPerk[perkId]`.
 */
export const onPerkDeployForStats = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'perkDeploys/{deployId}' },
  async (event) => {
    const data = event.data?.data();
    if (!data) {
      return;
    }
    const perkId = data.perkId;
    if (!isPerkId(perkId)) {
      return;
    }
    // `perkDeploys.createdAt` is a serverTimestamp (commit time). Unlike a drain
    // — which carries a separate `drainedAt` event instant — a deploy has NO
    // distinct event-time field: the commit IS the deploy, so createdAt is both
    // the best and only available instant. The transaction commits ~ms after the
    // deploy, so it buckets correctly except in a vanishingly small window right
    // at a Stockholm month boundary, which is acceptable for a usage aggregate.
    const deployedAt = readInstant(data.createdAt, event.data?.createTime?.toDate() ?? new Date());
    await foldPerkStat({
      source: 'deploy',
      sourceDocId: event.params.deployId,
      instant: deployedAt,
      perkField: { base: 'usedByPerk', perkId },
      foldExtra: { perkId },
    });
  },
);

/**
 * A TRAP TRIGGER — the create edge of `perkDrains/{drainId}`, written once per
 * successful trap drain (spike_strip only). Counts toward `trapTriggers`. Tallied
 * as it happens because `perkDrains` carries a 30-day TTL and cannot be
 * backfilled once reaped.
 */
export const onPerkDrainForStats = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'perkDrains/{drainId}' },
  async (event) => {
    const data = event.data?.data();
    if (!data) {
      return;
    }
    // Prefer `drainedAt` (the actual drain instant) over `createdAt` (the commit
    // serverTimestamp): near a month boundary the two can straddle it and the
    // event belongs to the season it fired in. Fall back to createdAt, then the
    // delivery time, if drainedAt is absent.
    const drainedAt = readInstant(
      data.drainedAt ?? data.createdAt,
      event.data?.createTime?.toDate() ?? new Date(),
    );
    await foldPerkStat({
      source: 'drain',
      sourceDocId: event.params.drainId,
      instant: drainedAt,
      scalarField: 'trapTriggers',
      foldExtra: {},
    });
  },
);
