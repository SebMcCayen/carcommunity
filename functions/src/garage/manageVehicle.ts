/**
 * garage.addVehicle / garage.updateVehicle / garage.deleteVehicle —
 * member callables (contracts/functions/functions.json).
 *
 * Deployed via the `garage` export group as `garage-addVehicle`,
 * `garage-updateVehicle`, and `garage-deleteVehicle`.
 *
 * All three require an active member (legacy canAccessGarage: garage
 * features are member-only, including deletion). Vehicles documents are
 * authenticated-readable by design (docs/firebase-data-model.md), so all
 * writes go through these callables: the strict schemas make registration
 * numbers / VIN / location unrepresentable, the per-user vehicle cap is
 * enforced race-safely, and deletion cleans the vehicleImages storage
 * prefix. Ownership failures return not-found — never permission-denied —
 * so callers cannot probe whether another user's vehicle exists.
 *
 * garage_created badge evaluation runs after each verified vehicle
 * creation (Phase 9f); a badge failure never fails the add itself.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminStorage, db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { tryAutomaticAward } from '../badges/awards';
import {
  MAX_VEHICLES_PER_USER,
  buildVehicleDocument,
  buildVehicleUpdate,
  isValidVehicleImagePath,
  parseAddVehicleInput,
  parseDeleteVehicleInput,
  parseSetMainVehicleInput,
  parseUpdateVehicleInput,
  vehicleImagePrefix,
} from './garage-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface VehicleIdResponse {
  vehicleId: string;
}

export const addVehicle = onCall(CALLABLE_OPTS, async (request): Promise<VehicleIdResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseAddVehicleInput(request.data, new Date());
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;

  const vehiclesRef = db.collection('vehicles');
  const vehicleRef = vehiclesRef.doc();

  // Cap enforced inside the transaction: concurrent adds serialize, so the
  // count can never race past MAX_VEHICLES_PER_USER.
  await db.runTransaction(async (tx) => {
    const countSnap = await tx.get(
      vehiclesRef.where('userId', '==', actor.uid).count(),
    );
    if (countSnap.data().count >= MAX_VEHICLES_PER_USER) {
      throw new HttpsError(
        'failed-precondition',
        `Garage is full — at most ${MAX_VEHICLES_PER_USER} vehicles per user.`,
      );
    }
    tx.set(
      vehicleRef,
      buildVehicleDocument(input, actor.uid, () => FieldValue.serverTimestamp()),
    );
  });

  // Legacy parity: garage_created is evaluated after every verified vehicle
  // creation. Awaited (Cloud Functions cannot safely fire-and-forget), but a
  // badge failure never FAILS the response — errors are logged and swallowed
  // (Phase 9f badges domain).
  await tryAutomaticAward(actor.uid, 'garage_created', 'garage.addVehicle');

  return { vehicleId: vehicleRef.id };
});

export const updateVehicle = onCall(CALLABLE_OPTS, async (request): Promise<VehicleIdResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseUpdateVehicleInput(request.data, new Date());
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;

  if (
    typeof input.imagePath === 'string' &&
    !isValidVehicleImagePath(input.imagePath, actor.uid, input.vehicleId)
  ) {
    throw new HttpsError(
      'invalid-argument',
      'imagePath must lie under your own vehicleImages prefix for this vehicle.',
    );
  }

  const vehicleRef = db.collection('vehicles').doc(input.vehicleId);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(vehicleRef);
    // not-found for both missing and foreign vehicles (no existence probing).
    if (!snap.exists || snap.data()?.userId !== actor.uid) {
      throw new HttpsError('not-found', 'Vehicle not found.');
    }
    const { update, changedFields } = buildVehicleUpdate(input, () =>
      FieldValue.serverTimestamp(),
    );
    if (changedFields.length === 0) {
      throw new HttpsError('invalid-argument', 'No vehicle fields to update.');
    }
    tx.update(vehicleRef, update);
  });

  return { vehicleId: input.vehicleId };
});

/**
 * garage.setMainVehicle — marks (or clears) one owned vehicle as the caller's
 * "main car". At most one main car per user is enforced transactionally: when
 * setting a car main, any other main car the caller owns is cleared in the same
 * transaction. The caller's whole garage is capped at 5, so reading the owned
 * set (by userId, single-field index) is cheap and needs no composite index.
 * Ownership failures return not-found (no existence probing), matching the
 * other garage callables.
 */
export const setMainVehicle = onCall(CALLABLE_OPTS, async (request): Promise<VehicleIdResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseSetMainVehicleInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { vehicleId, isMain } = parsed.input;

  const vehiclesRef = db.collection('vehicles');
  await db.runTransaction(async (tx) => {
    const ownedSnap = await tx.get(vehiclesRef.where('userId', '==', actor.uid));
    const target = ownedSnap.docs.find((doc) => doc.id === vehicleId);
    // not-found for both missing and foreign vehicles (no existence probing).
    if (!target) {
      throw new HttpsError('not-found', 'Vehicle not found.');
    }
    if (isMain) {
      // Enforce max-1: clear the flag on every other currently-main vehicle.
      ownedSnap.docs.forEach((doc) => {
        if (doc.id !== vehicleId && doc.data().isMainCar === true) {
          tx.update(doc.ref, { isMainCar: false, updatedAt: FieldValue.serverTimestamp() });
        }
      });
    }
    // Idempotent: only touch the target when its flag actually changes
    // (legacy docs omit isMainCar — treat missing as false). Re-affirming an
    // existing state is a no-op that neither writes nor bumps updatedAt; the
    // other-main sweep above still runs, so the max-1 invariant is restored
    // even if stray extra mains exist.
    if ((target.data().isMainCar === true) !== isMain) {
      tx.update(target.ref, { isMainCar: isMain, updatedAt: FieldValue.serverTimestamp() });
    }
  });

  return { vehicleId };
});

export const deleteVehicle = onCall(CALLABLE_OPTS, async (request): Promise<VehicleIdResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseDeleteVehicleInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { vehicleId } = parsed.input;

  const vehicleRef = db.collection('vehicles').doc(vehicleId);
  const snap = await vehicleRef.get();
  if (!snap.exists || snap.data()?.userId !== actor.uid) {
    throw new HttpsError('not-found', 'Vehicle not found.');
  }

  // Storage first (same rationale as drives.delete): a partial failure
  // leaves the document in place for a clean retry.
  await adminStorage.bucket().deleteFiles({ prefix: vehicleImagePrefix(actor.uid, vehicleId) });
  await vehicleRef.delete();

  return { vehicleId };
});
