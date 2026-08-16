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
  TRAP_DURATION_HOURS,
  TRAP_RADIUS_METERS,
  TRAP_SELF_SPACING_METERS,
  SHIELD_DURATION_HOURS,
  BOOST_DURATION_HOURS,
  deployRecordDocId,
  hoursFromNow,
  parseDeployPerkInput,
  perkById,
  scopeDeployKey,
  trapDeployCounterDocId,
  trapDocId,
  type PerkDefinition,
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
      return deployTrap({ uid, perk, latitude, longitude, now, scopedKey, deployRef, inventoryRef });
    case 'shield':
      return deployTimedEffect({
        uid,
        perk,
        kind: 'shield',
        durationHours: SHIELD_DURATION_HOURS,
        now,
        deployRef,
        inventoryRef,
      });
    case 'boost':
      return deployTimedEffect({
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
});

// ---------------------------------------------------------------------------
// TRAP
// ---------------------------------------------------------------------------

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

  // Pre-transaction anti-abuse read: the caller's currently-armed traps, used to
  // enforce the 1-active-trap cap AND the 300 m self-spacing rule. These are
  // best-effort guards (a rare race could momentarily allow a second trap); the
  // HARD volume ceiling — 3 deploys/day — is enforced transactionally below, and
  // the per-victim/per-day drain caps bound any abuse regardless. Filtering
  // expiry in code keeps this to the existing (placedByUid, status) index.
  const armedSnap = await db
    .collection('activePerks')
    .where('placedByUid', '==', uid)
    .where('status', '==', 'armed')
    .limit(20)
    .get();
  const nowMs = now.getTime();
  const liveTraps = armedSnap.docs
    .map((d) => d.data())
    .filter((t) => {
      const exp = t.expiresAt as Timestamp | undefined;
      return exp instanceof Timestamp && exp.toMillis() > nowMs;
    });
  if (liveTraps.length >= MAX_ACTIVE_TRAPS_PER_USER) {
    throw new HttpsError('failed-precondition', 'Du har redan en aktiv fälla.');
  }
  for (const t of liveTraps) {
    const tLat = t.lat as number | undefined;
    const tLng = t.lng as number | undefined;
    if (typeof tLat === 'number' && typeof tLng === 'number') {
      if (haversineDistanceMeters(latitude, longitude, tLat, tLng) < TRAP_SELF_SPACING_METERS) {
        throw new HttpsError(
          'failed-precondition',
          'För nära en av dina egna fällor. Flytta dig och försök igen.',
        );
      }
    }
  }

  const result = await db.runTransaction(async (tx) => {
    const [deploySnap, inventorySnap, counterSnap] = await Promise.all([
      tx.get(deployRef),
      tx.get(inventoryRef),
      tx.get(deployCounterRef),
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

    const owned = (inventorySnap.data()?.[perk.perkId] as number | undefined) ?? 0;
    if (owned < 1) {
      throw new HttpsError('failed-precondition', 'Du äger ingen sådan perk.');
    }

    const deployedToday = (counterSnap.data()?.count as number | undefined) ?? 0;
    if (deployedToday >= MAX_TRAP_DEPLOYS_PER_DAY) {
      throw new HttpsError('failed-precondition', 'Du har nått dagens gräns för fällor.');
    }

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
  // Public shield status — the ONLY field other clients may read: a single
  // timestamp so a live marker can render a shield aura. No other perk state.
  const publicShieldRef = db.collection('perkShieldPublic').doc(uid);

  const result = await db.runTransaction(async (tx) => {
    const [deploySnap, inventorySnap] = await Promise.all([tx.get(deployRef), tx.get(inventoryRef)]);

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
