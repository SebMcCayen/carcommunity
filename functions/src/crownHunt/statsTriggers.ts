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

import { onDocumentCreated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import {
  ALL_TIME_SCOPE,
  CROWN_CELL_STATS_COLLECTION,
  CROWN_LEADERBOARD_COLLECTION,
  CROWN_SPAWN_STATS_COLLECTION,
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
import { MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';

const TRIGGER_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_TRIGGER,
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
            byRarity: { [rarity]: FieldValue.increment(1) },
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
