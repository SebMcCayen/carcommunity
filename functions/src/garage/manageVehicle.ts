/**
 * garage.addVehicle / garage.updateVehicle / garage.setMainVehicle /
 * garage.deleteVehicle / garage.addVehiclePhoto / garage.removeVehiclePhoto /
 * garage.reorderVehiclePhotos — garage callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `garage` export group as `garage-addVehicle`,
 * `garage-updateVehicle`, `garage-setMainVehicle`, `garage-deleteVehicle`,
 * `garage-addVehiclePhoto`, `garage-removeVehiclePhoto`, and
 * `garage-reorderVehiclePhotos`.
 *
 * Multi-photo model: `photoPaths` is the ordered source of truth; the cover is
 * photoPaths[0], and `imagePath` is kept as a denormalised mirror of the cover
 * for backward compatibility (shipped clients + the profile card read
 * imagePath). Every photo-mutating callable maintains both fields together so
 * `imagePath === photoPaths[0]` (or both empty/null). Legacy vehicles created
 * before photoPaths are read as `[imagePath]` (readExistingPhotoPaths).
 *
 * All four require a signed-in, non-suspended, non-deleted caller acting on
 * their OWN cars (requireActiveActor). Managing your own garage is NOT
 * member-gated — any authenticated user may add/update/delete/setMain their
 * own vehicles (Seb-approved ungate). Vehicles documents are
 * authenticated-readable by design (docs/firebase-data-model.md), so all
 * writes still go through these callables: the strict schemas make registration
 * numbers / VIN / location unrepresentable, the per-user vehicle cap is
 * enforced race-safely, and deletion cleans the vehicleImages storage
 * prefix. Ownership failures return not-found — never permission-denied —
 * so callers cannot probe whether another user's vehicle exists.
 *
 * garage_created badge evaluation runs after each verified vehicle
 * creation (Phase 9f); a badge failure never fails the add itself.
 */

import { logger } from 'firebase-functions';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminStorage, db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { tryAutomaticAward } from '../badges/awards';
import {
  MAX_VEHICLES_PER_USER,
  MAX_VEHICLE_PHOTOS,
  appendPhotoPath,
  buildVehicleDocument,
  buildVehicleUpdate,
  coverPhotoPath,
  isPhotoPermutation,
  isValidVehicleImagePath,
  parseAddVehicleInput,
  parseAddVehiclePhotoInput,
  parseDeleteVehicleInput,
  parseRemoveVehiclePhotoInput,
  parseReorderVehiclePhotosInput,
  parseSetMainVehicleInput,
  parseUpdateVehicleInput,
  readExistingPhotoPaths,
  reconcileCoverPhotoPaths,
  removePhotoPath,
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
  const actor = await requireActiveActor(request);

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
  const actor = await requireActiveActor(request);

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
    // The single-photo `imagePath` path (edit-form "change photo" + shipped
    // clients) must keep the photoPaths gallery coherent with the cover mirror.
    // Reconcile from the current stored gallery so imagePath === photoPaths[0].
    if (changedFields.includes('imagePath')) {
      const existing = readExistingPhotoPaths(snap.data() ?? {});
      const reconciled = reconcileCoverPhotoPaths(existing, input.imagePath ?? null);
      update.photoPaths = reconciled;
      update.imagePath = coverPhotoPath(reconciled);
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
  const actor = await requireActiveActor(request);

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
    // existing state is a no-op that neither writes nor bumps updatedAt.
    // When setting (isMain true), the sweep above still runs, so the max-1
    // invariant is restored even if stray extra mains exist; clearing
    // (isMain false) only ever touches the target.
    if ((target.data().isMainCar === true) !== isMain) {
      tx.update(target.ref, { isMainCar: isMain, updatedAt: FieldValue.serverTimestamp() });
    }
  });

  return { vehicleId };
});

export const deleteVehicle = onCall(CALLABLE_OPTS, async (request): Promise<VehicleIdResponse> => {
  const actor = await requireActiveActor(request);

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

/**
 * garage.addVehiclePhoto — appends one already-uploaded photo path to an owned
 * vehicle's gallery. The photo bytes are uploaded to
 * vehicleImages/{uid}/{vehicleId}/{imageId} directly by the client (storage
 * rules gate the write); this callable RECORDS the path after enforcing the
 * per-vehicle cap and the own-prefix validation exactly like updateVehicle's
 * imagePath. When it is the first photo, it also becomes the cover (imagePath).
 */
export const addVehiclePhoto = onCall(CALLABLE_OPTS, async (request): Promise<VehicleIdResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseAddVehiclePhotoInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { vehicleId, photoPath } = parsed.input;

  if (!isValidVehicleImagePath(photoPath, actor.uid, vehicleId)) {
    throw new HttpsError(
      'invalid-argument',
      'photoPath must lie under your own vehicleImages prefix for this vehicle.',
    );
  }

  const vehicleRef = db.collection('vehicles').doc(vehicleId);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(vehicleRef);
    if (!snap.exists || snap.data()?.userId !== actor.uid) {
      throw new HttpsError('not-found', 'Vehicle not found.');
    }
    const existing = readExistingPhotoPaths(snap.data() ?? {});
    const result = appendPhotoPath(existing, photoPath);
    if (!result.ok) {
      if (result.error === 'cap') {
        throw new HttpsError(
          'failed-precondition',
          `A vehicle can have at most ${MAX_VEHICLE_PHOTOS} photos.`,
        );
      }
      throw new HttpsError('already-exists', 'That photo is already on this vehicle.');
    }
    // imagePath mirrors the cover (photoPaths[0]); appending never changes the
    // existing cover, and sets it when this is the first photo.
    tx.update(vehicleRef, {
      photoPaths: result.paths,
      imagePath: coverPhotoPath(result.paths),
      updatedAt: FieldValue.serverTimestamp(),
    });
  });

  return { vehicleId };
});

/**
 * garage.removeVehiclePhoto — removes one photo path from an owned vehicle's
 * gallery and deletes its Storage object. Removing the cover promotes the next
 * remaining photo to cover automatically (photoPaths order is preserved).
 */
export const removeVehiclePhoto = onCall(
  CALLABLE_OPTS,
  async (request): Promise<VehicleIdResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseRemoveVehiclePhotoInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { vehicleId, photoPath } = parsed.input;

    if (!isValidVehicleImagePath(photoPath, actor.uid, vehicleId)) {
      throw new HttpsError(
        'invalid-argument',
        'photoPath must lie under your own vehicleImages prefix for this vehicle.',
      );
    }

    const vehicleRef = db.collection('vehicles').doc(vehicleId);
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(vehicleRef);
      if (!snap.exists || snap.data()?.userId !== actor.uid) {
        throw new HttpsError('not-found', 'Vehicle not found.');
      }
      const existing = readExistingPhotoPaths(snap.data() ?? {});
      const { found, paths } = removePhotoPath(existing, photoPath);
      if (!found) {
        throw new HttpsError('not-found', 'Photo not found on this vehicle.');
      }
      tx.update(vehicleRef, {
        photoPaths: paths,
        imagePath: coverPhotoPath(paths),
        updatedAt: FieldValue.serverTimestamp(),
      });
    });

    // Document first (source of truth): if this best-effort object delete fails
    // the gallery is already correct and the orphan is cleaned when the whole
    // vehicleImages/{uid}/{vehicleId}/ prefix is removed by deleteVehicle.
    // Never fail the callable on a delete miss — the doc update already
    // succeeded and the leftover object is a cleanup concern, not user-facing.
    // We still log it so a systemic delete failure (permissions/misconfig) is
    // observable instead of silently accumulating orphans. Log only the
    // vehicleId + error code/message; the storage path embeds the caller uid,
    // so it is deliberately kept out of the log.
    await adminStorage
      .bucket()
      .file(photoPath)
      .delete({ ignoreNotFound: true })
      .catch((error: unknown) => {
        logger.warn('Vehicle photo storage delete failed; object may be orphaned', {
          vehicleId,
          code: (error as { code?: unknown })?.code,
          error: error instanceof Error ? error.message : String(error),
        });
      });

    return { vehicleId };
  },
);

/**
 * garage.reorderVehiclePhotos — sets the full display order of an owned
 * vehicle's photos. orderedPaths must be a permutation of the vehicle's current
 * photo set (same members, same count); its first entry becomes the cover
 * (mirrored into imagePath).
 */
export const reorderVehiclePhotos = onCall(
  CALLABLE_OPTS,
  async (request): Promise<VehicleIdResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseReorderVehiclePhotosInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { vehicleId, orderedPaths } = parsed.input;

    for (const path of orderedPaths) {
      if (!isValidVehicleImagePath(path, actor.uid, vehicleId)) {
        throw new HttpsError(
          'invalid-argument',
          'Every path must lie under your own vehicleImages prefix for this vehicle.',
        );
      }
    }

    const vehicleRef = db.collection('vehicles').doc(vehicleId);
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(vehicleRef);
      if (!snap.exists || snap.data()?.userId !== actor.uid) {
        throw new HttpsError('not-found', 'Vehicle not found.');
      }
      const existing = readExistingPhotoPaths(snap.data() ?? {});
      if (!isPhotoPermutation(existing, orderedPaths)) {
        throw new HttpsError(
          'invalid-argument',
          'orderedPaths must be a reordering of the vehicle\'s existing photos.',
        );
      }
      tx.update(vehicleRef, {
        photoPaths: orderedPaths,
        imagePath: coverPhotoPath(orderedPaths),
        updatedAt: FieldValue.serverTimestamp(),
      });
    });

    return { vehicleId };
  },
);
