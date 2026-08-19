/**
 * crownHunt.deployPerk — callable (contracts/functions/functions.json).
 *
 * The USE side of the Kronjakt shop (PR1 sold perks into perkInventory; this
 * consumes one and applies its effect). Three kinds, one callable:
 *
 *   • TRAP (spike_strip / Spikmatta) — drops an INVISIBLE armed trap at the
 *     caller's current GPS (`activePerks/{trapId}`, backend-write, readable
 *     ONLY by the placer). A rival who later drives within its radius is
 *     drained by the inline processor in live.updatePosition (pvp-drain.ts).
 *   • SHIELD (shield / Sköld) — raises `perkShield/{uid}` (backend-only) AND a
 *     minimal PUBLIC `perkShieldPublic/{uid}` = { shieldedUntil } so OTHER
 *     clients can render a shield aura on the holder's live marker. Only the
 *     timestamp is public — no other perk state leaks.
 *   • BOOST (boost / Dubbla Poäng) — arms `perkBoost/{uid}`; while active, the
 *     crown-award path doubles the KP a claim pays (resolveActiveBoostMultiplier
 *     in pvp-drain.ts), and the doubled amount still folds into the 300/day cap.
 *
 * Everything is gated on the contract-default-OFF `crownHuntPerks` flag — a
 * no-op (failed-precondition) while the flag is off, exactly like buyPerk.
 *
 * IDEMPOTENT + ATOMIC. Every deploy runs in ONE Firestore transaction keyed on
 * a `perkDeploys/{scopedKey}` record: a replay finds the record and changes
 * nothing (inventory is consumed once, the effect applied once). Inventory is
 * checked (>= 1) and decremented in the SAME transaction as the effect, so a
 * grant can never be consumed without its effect landing, or vice versa.
 *
 * SERVER-AUTHORITATIVE. Radius, durations, the 1-active-trap / 3-per-day /
 * 300 m-spacing anti-abuse guards are all server constants (perks-core.ts); the
 * client supplies only which perk and (for a trap) where it is standing.
 *
 * Deployed via the `crownHunt` export group as `crownHunt-deployPerk`.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { requireMemberActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { isValidCoordinate, haversineDistanceMeters } from './crown-hunt-geo';
import { crownCellKey, utcDayKey } from './crown-spawn-core';
import {
  CROWN_HUNT_PERKS_FLAG_KEY,
  MAX_ACTIVE_TRAPS_PER_USER,
  MAX_TRAP_DEPLOYS_PER_DAY,
  PERK_DEPLOY_REASON_ACTIVATION_LIMIT,
  TRAP_DURATION_HOURS,
  TRAP_RADIUS_METERS,
  TRAP_SELF_SPACING_METERS,
  SHIELD_DURATION_HOURS,
  BOOST_DURATION_HOURS,
  activationAllowed,
  deployRecordDocId,
  hoursFromNow,
  isTimestampActive,
  parseDeployPerkInput,
  perkById,
  scopeDeployKey,
  trapDeployCounterDocId,
  trapDocId,
  type ActivePerkEffects,
  type PerkDefinition,
  type PerkKind,
} from './perks-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export type DeployResultKind = 'trap' | 'shield' | 'boost';

export interface DeployPerkResponse {
  perkId: string;
  kind: DeployResultKind;
  /** The trap/shield/boost document ID (trap) or the holder uid (shield/boost). */
  effectId: string;
  /** ISO expiry of the deployed effect. */
  expiresAt: string;
  /** Remaining inventory count of this perk after the consume. */
  inventoryCount: number;
  /** True when an idempotent replay returned the original deploy. */
  alreadyDeployed: boolean;
}

/** TTL horizon for the daily counter docs (operator TTL policy reaps them). */
function counterExpireAt(now: Date): Timestamp {
  return Timestamp.fromMillis(now.getTime() + 3 * 24 * 60 * 60 * 1000);
}

/** Epoch-ms of a Firestore `expiresAt` field, or null when absent/wrong type. */
function expiresAtMillis(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

/**
 * Enforces the concurrent-activation limit: throws a reason-coded
 * `failed-precondition` when deploying `kind` would exceed
 * MAX_CONCURRENT_ACTIVE_PERKS distinct live effects. Re-raising an already-active
 * kind is always allowed (see {@link activationAllowed}).
 */
function assertActivationAllowed(active: ActivePerkEffects, kind: PerkKind): void {
  if (!activationAllowed(active, kind)) {
    throw new HttpsError(
      'failed-precondition',
      'Du har redan för många aktiva perks. Vänta tills en går ut.',
      { reason: PERK_DEPLOY_REASON_ACTIVATION_LIMIT },
    );
  }
}

export const deployPerk = onCall(CALLABLE_OPTS, async (request): Promise<DeployPerkResponse> => {
  // Member-gated, matching buyPerk (requireMemberActor = requireActiveActor +
  // memberGateAllows). Member gating is disabled repo-wide today, so this
  // presently asserts only signed-in + not suspended/deleted; when it is
  // re-locked, deployPerk rejects non-members exactly as buyPerk does.
  const actor = await requireMemberActor(request);
  const uid = actor.uid;

  const parsed = parseDeployPerkInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { perkId, latitude, longitude, idempotencyKey } = parsed.input;

  // Single logging point for every server-authoritative rejection (flag off,
  // unknown perk, missing position, 1-active-trap / self-spacing / no-inventory /
  // daily-limit guards) AND any transaction/infrastructure failure. Logging here
  // — OUTSIDE the deploy transactions — means a Firestore transaction retry on
  // contention cannot double-log a rejection. NO PII: perk catalog id + the
  // server-authored HttpsError code/message only, never the uid or coordinates.
  try {
    // Flag gate — a member must never be able to deploy against a disabled system.
    if (!(await readFeatureFlag(CROWN_HUNT_PERKS_FLAG_KEY))) {
      throw new HttpsError('failed-precondition', 'Kronjaktsbutiken är inte tillgänglig just nu.');
    }

    const perk = perkById(perkId);
    if (!perk) {
      throw new HttpsError('failed-precondition', 'Okänd perk.');
    }

    const now = new Date();
    const scopedKey = scopeDeployKey(uid, idempotencyKey);
    const deployRef = db.collection('perkDeploys').doc(deployRecordDocId(scopedKey));
    const inventoryRef = db.collection('perkInventory').doc(uid);

    switch (perk.kind) {
      case 'trap':
        return await deployTrap({
          uid,
          perk,
          latitude,
          longitude,
          now,
          scopedKey,
          deployRef,
          inventoryRef,
        });
      case 'shield':
        return await deployTimedEffect({
          uid,
          perk,
          kind: 'shield',
          durationHours: SHIELD_DURATION_HOURS,
          now,
          deployRef,
          inventoryRef,
        });
      case 'boost':
        return await deployTimedEffect({
          uid,
          perk,
          kind: 'boost',
          durationHours: BOOST_DURATION_HOURS,
          now,
          deployRef,
          inventoryRef,
        });
      default:
        throw new HttpsError('failed-precondition', 'Okänd perk.');
    }
  } catch (err) {
    if (err instanceof HttpsError) {
      // Ordinary server-authoritative rejection — reason is the server-authored
      // (non-PII) message; code separates failed-precondition/resource-exhausted.
      logger.info('crownHunt.deployPerk rejected', {
        perkId,
        code: err.code,
        reason: err.message,
      });
    } else {
      // Unexpected transaction/infrastructure failure — surface it before it
      // propagates so a failed deploy is diagnosable.
      logger.error('crownHunt.deployPerk transaction failed', {
        perkId,
        error: String(err),
      });
    }
    throw err;
  }
});

// ---------------------------------------------------------------------------
// TRAP
// ---------------------------------------------------------------------------

/**
 * The 1-active-trap + 300 m self-spacing caps evaluated against a set of the
 * caller's LIVE armed traps. Returns an HttpsError to throw, or null when the
 * deploy is allowed. Shared by the fast pre-transaction early-reject and the
 * authoritative in-transaction check so the two can never diverge.
 */
function evaluateTrapCaps(
  liveTraps: FirebaseFirestore.DocumentData[],
  latitude: number,
  longitude: number,
): HttpsError | null {
  if (liveTraps.length >= MAX_ACTIVE_TRAPS_PER_USER) {
    return new HttpsError('failed-precondition', 'Du har redan en aktiv fälla.');
  }
  for (const t of liveTraps) {
    const tLat = t.lat as number | undefined;
    const tLng = t.lng as number | undefined;
    if (typeof tLat === 'number' && typeof tLng === 'number') {
      if (haversineDistanceMeters(latitude, longitude, tLat, tLng) < TRAP_SELF_SPACING_METERS) {
        return new HttpsError(
          'failed-precondition',
          'För nära en av dina egna fällor. Flytta dig och försök igen.',
        );
      }
    }
  }
  return null;
}

async function deployTrap(args: {
  uid: string;
  perk: PerkDefinition;
  latitude?: number;
  longitude?: number;
  now: Date;
  scopedKey: string;
  deployRef: FirebaseFirestore.DocumentReference;
  inventoryRef: FirebaseFirestore.DocumentReference;
}): Promise<DeployPerkResponse> {
  const { uid, perk, latitude, longitude, now, scopedKey, deployRef, inventoryRef } = args;

  if (
    latitude === undefined ||
    longitude === undefined ||
    !isValidCoordinate(latitude, longitude)
  ) {
    throw new HttpsError('invalid-argument', 'En fälla kräver din nuvarande position.');
  }

  const cellKey = crownCellKey(latitude, longitude);
  const expiresAt = hoursFromNow(now, TRAP_DURATION_HOURS);
  const trapRef = db.collection('activePerks').doc(trapDocId(scopedKey));
  const dayKey = utcDayKey(now);
  const deployCounterRef = db
    .collection('perkTrapDeploys')
    .doc(trapDeployCounterDocId(uid, dayKey));

  // Idempotency short-circuit BEFORE the active-trap pre-check: a retried deploy
  // must replay the original result, not be rejected by the 1-active-trap guard
  // for the trap it ALREADY placed. The transaction below re-reads the deploy
  // record too, so a genuine concurrent first-deploy is still race-safe.
  const existingDeploy = await deployRef.get();
  if (existingDeploy.exists) {
    const d = existingDeploy.data()!;
    const inv = await args.inventoryRef.get();
    return {
      perkId: perk.perkId,
      kind: 'trap',
      effectId: (d.effectId as string) ?? trapRef.id,
      expiresAt: (d.expiresAt as Timestamp).toDate().toISOString(),
      inventoryCount: (inv.data()?.[perk.perkId] as number | undefined) ?? 0,
      alreadyDeployed: true,
    };
  }

  // The caller's currently-LIVE armed traps, used to enforce the 1-active-trap
  // cap AND the 300 m self-spacing rule. Built once as a QUERY, run BOTH as a
  // fast pre-transaction early-reject (below, so the common case never starts a
  // transaction) AND — authoritatively — INSIDE the transaction via tx.get(query),
  // so two concurrent deploys (different idempotency keys) cannot both pass: the
  // second serializes on the transaction, re-queries, sees the first trap and
  // rejects.
  //
  // Expiry is filtered by FIRESTORE (`expiresAt > now`), NOT in code. An expired
  // trap keeps status:'armed' — expiry is by the `expiresAt` timestamp and the
  // TTL deletion is optional/eventual — so a code-side filter under a `.limit`
  // could return only STALE docs once a user accumulates more than the limit of
  // expired armed traps (~a week at 3/day with no TTL), silently emptying the
  // live set and bypassing BOTH guards. The server-side range predicate returns
  // only genuinely-live traps, so the limit only ever elides surplus LIVE ones
  // (there is at most one, by the cap). Needs the composite index
  // activePerks(placedByUid, status, expiresAt) in firestore.indexes.json.
  const armedQuery = db
    .collection('activePerks')
    .where('placedByUid', '==', uid)
    .where('status', '==', 'armed')
    .where('expiresAt', '>', Timestamp.fromDate(now))
    .limit(20);

  // Fast pre-transaction early-reject (better UX; avoids starting a transaction
  // in the common case). NOT authoritative — the in-transaction check below is.
  const preSnap = await armedQuery.get();
  const preLiveTraps = preSnap.docs.map((d) => d.data());
  const capRejection = evaluateTrapCaps(preLiveTraps, latitude, longitude);
  if (capRejection) {
    throw capRejection;
  }

  const shieldRef = db.collection('perkShield').doc(uid);
  const boostRef = db.collection('perkBoost').doc(uid);

  const result = await db.runTransaction(async (tx) => {
    // tx.get(query) runs in the read phase (all reads precede all writes). This
    // is the AUTHORITATIVE, race-safe cap/spacing check — a concurrent deploy
    // that committed a trap since the pre-check is seen here and rejected. The
    // shield/boost reads back the concurrent-activation limit.
    const [deploySnap, inventorySnap, counterSnap, armedTxSnap, shieldSnap, boostSnap] =
      await Promise.all([
        tx.get(deployRef),
        tx.get(inventoryRef),
        tx.get(deployCounterRef),
        tx.get(armedQuery),
        tx.get(shieldRef),
        tx.get(boostRef),
      ]);

    if (deploySnap.exists) {
      const d = deploySnap.data()!;
      return {
        effectId: (d.effectId as string) ?? trapRef.id,
        expiresAt: (d.expiresAt as Timestamp).toDate().toISOString(),
        inventoryCount: (inventorySnap.data()?.[perk.perkId] as number | undefined) ?? 0,
        alreadyDeployed: true,
      };
    }

    // Race-safe 1-active-trap + 300 m self-spacing, on the in-transaction query
    // result (excluding this deploy's own trap doc, defensively).
    const liveTrapsTx = armedTxSnap.docs
      .filter((d) => d.id !== trapRef.id)
      .map((d) => d.data());
    const txCapRejection = evaluateTrapCaps(liveTrapsTx, latitude, longitude);
    if (txCapRejection) {
      throw txCapRejection;
    }

    const owned = (inventorySnap.data()?.[perk.perkId] as number | undefined) ?? 0;
    if (owned < 1) {
      throw new HttpsError('failed-precondition', 'Du äger ingen sådan perk.');
    }

    const deployedToday = (counterSnap.data()?.count as number | undefined) ?? 0;
    if (deployedToday >= MAX_TRAP_DEPLOYS_PER_DAY) {
      throw new HttpsError('failed-precondition', 'Du har nått dagens gräns för fällor.');
    }

    // Concurrent-activation limit. This deploy adds a NEW armed trap (the
    // 1-active-trap cap above guarantees the member has none live right now), so
    // it is refused only when a shield AND a boost are already both live.
    const nowMs = now.getTime();
    assertActivationAllowed(
      {
        trap: false,
        shield: isTimestampActive(expiresAtMillis(shieldSnap.data()?.expiresAt), nowMs),
        boost: isTimestampActive(expiresAtMillis(boostSnap.data()?.expiresAt), nowMs),
      },
      'trap',
    );

    tx.set(trapRef, {
      placedByUid: uid,
      cellKey,
      status: 'armed',
      lat: latitude,
      lng: longitude,
      radiusM: TRAP_RADIUS_METERS,
      victimCount: 0,
      expiresAt: Timestamp.fromDate(expiresAt),
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.set(
      inventoryRef,
      { [perk.perkId]: FieldValue.increment(-1), updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    tx.set(
      deployCounterRef,
      {
        userId: uid,
        day: dayKey,
        count: FieldValue.increment(1),
        expireAt: counterExpireAt(now),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    tx.set(deployRef, {
      userId: uid,
      perkId: perk.perkId,
      kind: 'trap',
      effectId: trapRef.id,
      expiresAt: Timestamp.fromDate(expiresAt),
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      effectId: trapRef.id,
      expiresAt: expiresAt.toISOString(),
      inventoryCount: owned - 1,
      alreadyDeployed: false,
    };
  });

  return { perkId: perk.perkId, kind: 'trap', ...result };
}

// ---------------------------------------------------------------------------
// SHIELD / BOOST — timed self-effects keyed on the holder uid
// ---------------------------------------------------------------------------

async function deployTimedEffect(args: {
  uid: string;
  perk: PerkDefinition;
  kind: 'shield' | 'boost';
  durationHours: number;
  now: Date;
  deployRef: FirebaseFirestore.DocumentReference;
  inventoryRef: FirebaseFirestore.DocumentReference;
}): Promise<DeployPerkResponse> {
  const { uid, perk, kind, durationHours, now, deployRef, inventoryRef } = args;
  const expiresAt = hoursFromNow(now, durationHours);
  const effectRef = db.collection(kind === 'shield' ? 'perkShield' : 'perkBoost').doc(uid);
  // Public shield status doc. It stores two fields — `shieldedUntil` (the
  // expiry a live marker reads to render a shield aura) and a bookkeeping
  // `updatedAt` — and NOTHING else about the perk (no inventory, cost, or other
  // effects). `shieldedUntil` is the only field clients actually consume; both
  // are harmless to expose, which is the whole point of a separate public doc.
  const publicShieldRef = db.collection('perkShieldPublic').doc(uid);
  // Concurrent-activation reads: the member's live shield, boost and any armed
  // trap. One of the effect docs IS effectRef (the kind being raised); reading it
  // here and writing it below is read-before-write safe.
  const shieldStateRef = db.collection('perkShield').doc(uid);
  const boostStateRef = db.collection('perkBoost').doc(uid);
  const armedTrapQuery = db
    .collection('activePerks')
    .where('placedByUid', '==', uid)
    .where('status', '==', 'armed')
    .where('expiresAt', '>', Timestamp.fromDate(now))
    .limit(1);

  const result = await db.runTransaction(async (tx) => {
    const [deploySnap, inventorySnap, shieldStateSnap, boostStateSnap, armedTrapSnap] =
      await Promise.all([
        tx.get(deployRef),
        tx.get(inventoryRef),
        tx.get(shieldStateRef),
        tx.get(boostStateRef),
        tx.get(armedTrapQuery),
      ]);

    if (deploySnap.exists) {
      const d = deploySnap.data()!;
      return {
        effectId: uid,
        expiresAt: (d.expiresAt as Timestamp).toDate().toISOString(),
        inventoryCount: (inventorySnap.data()?.[perk.perkId] as number | undefined) ?? 0,
        alreadyDeployed: true,
      };
    }

    const owned = (inventorySnap.data()?.[perk.perkId] as number | undefined) ?? 0;
    if (owned < 1) {
      throw new HttpsError('failed-precondition', 'Du äger ingen sådan perk.');
    }

    // Concurrent-activation limit. Re-raising the kind that is already live is
    // allowed (it replaces, adding no new distinct effect); raising a NEW kind is
    // refused once the member already has MAX_CONCURRENT_ACTIVE_PERKS live.
    const nowMs = now.getTime();
    assertActivationAllowed(
      {
        trap: !armedTrapSnap.empty,
        shield: isTimestampActive(expiresAtMillis(shieldStateSnap.data()?.expiresAt), nowMs),
        boost: isTimestampActive(expiresAtMillis(boostStateSnap.data()?.expiresAt), nowMs),
      },
      kind,
    );

    tx.set(
      effectRef,
      { uid, expiresAt: Timestamp.fromDate(expiresAt), updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    if (kind === 'shield') {
      tx.set(
        publicShieldRef,
        { shieldedUntil: Timestamp.fromDate(expiresAt), updatedAt: FieldValue.serverTimestamp() },
        { merge: true },
      );
    }
    tx.set(
      inventoryRef,
      { [perk.perkId]: FieldValue.increment(-1), updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    tx.set(deployRef, {
      userId: uid,
      perkId: perk.perkId,
      kind,
      effectId: uid,
      expiresAt: Timestamp.fromDate(expiresAt),
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      effectId: uid,
      expiresAt: expiresAt.toISOString(),
      inventoryCount: owned - 1,
      alreadyDeployed: false,
    };
  });

  return { perkId: perk.perkId, kind, ...result };
}
