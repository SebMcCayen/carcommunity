/**
 * Unit tests for the garage pure logic (garage-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  GARAGE_VEHICLE_LIMITS,
  garageVehicleLimitForTier,
  LEGACY_VEHICLE_POWERTRAINS,
  MAX_VEHICLE_PHOTOS,
  SELECTABLE_VEHICLE_POWERTRAINS,
  VEHICLE_POWERTRAINS,
  appendPhotoPath,
  buildVehicleDocument,
  buildVehicleUpdate,
  coverPhotoPath,
  isPhotoPermutation,
  isValidVehicleImagePath,
  maxModelYear,
  normaliseRegistrationPlate,
  parseAddVehicleInput,
  parseAddVehiclePhotoInput,
  parseDeleteVehicleInput,
  parseReorderVehiclePhotosInput,
  parseRemoveVehiclePhotoInput,
  parseSetMainVehicleInput,
  parseUpdateVehicleInput,
  readExistingPhotoPaths,
  reconcileCoverPhotoPaths,
  removePhotoPath,
  vehicleImagePrefix,
} from '../garage/garage-core';

const NOW = new Date('2026-07-04T12:00:00Z');
const serverTimestamp = () => 'SERVER_TS';

const validAdd = {
  make: 'Koenigsegg',
  model: 'Jesko',
  modelYear: 2025,
  powertrain: 'petrol',
};

describe('garage-core subscription limits', () => {
  it('maps Community, Plus, and Supporter to 2, 5, and 10 vehicles', () => {
    expect(GARAGE_VEHICLE_LIMITS).toEqual({ community: 2, plus: 5, supporter: 10 });
    expect(garageVehicleLimitForTier('community')).toBe(2);
    expect(garageVehicleLimitForTier('plus')).toBe(5);
    expect(garageVehicleLimitForTier('supporter')).toBe(10);
  });
});

describe('garage-core powertrain vocabulary', () => {
  it('offers exactly Petrol, Diesel, Hybrid, Electric — in that order', () => {
    // Pins the SPECIFIC four Seb asked for (and their order, which is the
    // order the Android form renders), not merely the count.
    expect(SELECTABLE_VEHICLE_POWERTRAINS).toEqual(['petrol', 'diesel', 'hybrid', 'electric']);
  });

  it('retires plug_in_hybrid and other without deleting them', () => {
    expect(LEGACY_VEHICLE_POWERTRAINS).toEqual(['plug_in_hybrid', 'other']);
    // Retired means "not offered", never "not accepted".
    for (const retired of LEGACY_VEHICLE_POWERTRAINS) {
      expect(SELECTABLE_VEHICLE_POWERTRAINS).not.toContain(retired);
      expect(VEHICLE_POWERTRAINS).toContain(retired);
    }
  });

  it('accepts a strict superset of what it offers', () => {
    expect(VEHICLE_POWERTRAINS).toEqual([
      'petrol',
      'diesel',
      'hybrid',
      'electric',
      'plug_in_hybrid',
      'other',
    ]);
  });

  it('accepts every offered powertrain on add', () => {
    for (const powertrain of SELECTABLE_VEHICLE_POWERTRAINS) {
      expect(parseAddVehicleInput({ ...validAdd, powertrain }, NOW).ok).toBe(true);
    }
  });

  // --- backward compatibility -------------------------------------------
  // Shipped clients (<= v0.8.0) still OFFER the retired values, and existing
  // Firestore documents still HOLD them. Rejecting either would break real
  // users mid-rollout, so both stay accepted and are stored verbatim.

  it('still accepts retired powertrains on add, so shipped clients keep working', () => {
    for (const powertrain of LEGACY_VEHICLE_POWERTRAINS) {
      expect(parseAddVehicleInput({ ...validAdd, powertrain }, NOW).ok).toBe(true);
    }
  });

  it('still accepts retired powertrains on update, so an existing car stays editable', () => {
    for (const powertrain of LEGACY_VEHICLE_POWERTRAINS) {
      expect(parseUpdateVehicleInput({ vehicleId: 'v1', powertrain }, NOW).ok).toBe(true);
    }
  });

  it('stores a retired powertrain verbatim — never remapped', () => {
    // The corruption guard: no silent plug_in_hybrid -> hybrid rewrite.
    const parsed = parseAddVehicleInput({ ...validAdd, powertrain: 'plug_in_hybrid' }, NOW);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    const doc = buildVehicleDocument(parsed.input, 'uid-1', serverTimestamp);
    expect(doc.powertrain).toBe('plug_in_hybrid');

    const updated = parseUpdateVehicleInput({ vehicleId: 'v1', powertrain: 'other' }, NOW);
    expect(updated.ok).toBe(true);
    if (!updated.ok) return;
    expect(buildVehicleUpdate(updated.input, serverTimestamp).update.powertrain).toBe('other');
  });

  it('migrates a retired vehicle forward when the owner picks one of the four', () => {
    const parsed = parseUpdateVehicleInput({ vehicleId: 'v1', powertrain: 'hybrid' }, NOW);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    const { update, changedFields } = buildVehicleUpdate(parsed.input, serverTimestamp);
    expect(update.powertrain).toBe('hybrid');
    expect(changedFields).toContain('powertrain');
  });

  it('still rejects a value that was never in the vocabulary', () => {
    expect(parseAddVehicleInput({ ...validAdd, powertrain: 'nuclear' }, NOW).ok).toBe(false);
    expect(parseUpdateVehicleInput({ vehicleId: 'v1', powertrain: 'steam' }, NOW).ok).toBe(false);
  });
});

describe('garage-core input parsing', () => {
  it('accepts a valid vehicle and rejects malformed inputs', () => {
    expect(parseAddVehicleInput(validAdd, NOW).ok).toBe(true);
    expect(parseAddVehicleInput({ ...validAdd, make: '' }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...validAdd, make: 'x'.repeat(81) }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...validAdd, powertrain: 'nuclear' }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...validAdd, extra: 1 }, NOW).ok).toBe(false);
  });

  it('accepts registrationPlate (deliberately public field) but keeps VIN unrepresentable', () => {
    // Plate is a Seb-approved PUBLIC field now — accepted on add and update.
    expect(parseAddVehicleInput({ ...validAdd, registrationPlate: 'ABC123' }, NOW).ok).toBe(true);
    expect(parseUpdateVehicleInput({ vehicleId: 'v1', registrationPlate: 'ABC123' }, NOW).ok).toBe(
      true,
    );
    // VIN was never meant to be public and stays unrepresentable (strict schema).
    expect(parseAddVehicleInput({ ...validAdd, vin: 'YV1abc' }, NOW).ok).toBe(false);
  });

  it('bounds modelYear between 1886 and currentYear + 2', () => {
    expect(maxModelYear(NOW)).toBe(2028);
    expect(parseAddVehicleInput({ ...validAdd, modelYear: 1885 }, NOW).ok).toBe(false);
    expect(parseAddVehicleInput({ ...validAdd, modelYear: 2028 }, NOW).ok).toBe(true);
    expect(parseAddVehicleInput({ ...validAdd, modelYear: 2029 }, NOW).ok).toBe(false);
  });

  it('validates vehicleId as a Firestore-safe document ID', () => {
    expect(parseDeleteVehicleInput({ vehicleId: 'v-1' }).ok).toBe(true);
    expect(parseDeleteVehicleInput({ vehicleId: 'vehicles/other' }).ok).toBe(false);
    expect(parseDeleteVehicleInput({ vehicleId: '..' }).ok).toBe(false);
  });

  it('parses setMainVehicle input and requires a boolean flag', () => {
    expect(parseSetMainVehicleInput({ vehicleId: 'v1', isMain: true }).ok).toBe(true);
    expect(parseSetMainVehicleInput({ vehicleId: 'v1', isMain: false }).ok).toBe(true);
    // Missing/malformed flag and a foreign extra field are rejected (strict).
    expect(parseSetMainVehicleInput({ vehicleId: 'v1' }).ok).toBe(false);
    expect(parseSetMainVehicleInput({ vehicleId: 'v1', isMain: 'yes' }).ok).toBe(false);
    expect(parseSetMainVehicleInput({ vehicleId: 'v1', isMain: true, extra: 1 }).ok).toBe(false);
    expect(parseSetMainVehicleInput({ vehicleId: 'vehicles/other', isMain: true }).ok).toBe(false);
  });
});

describe('garage-core registration plate normalisation', () => {
  it('trims, collapses internal whitespace, and uppercases', () => {
    expect(normaliseRegistrationPlate('  abc 123  ')).toBe('ABC 123');
    expect(normaliseRegistrationPlate('abc   123')).toBe('ABC 123');
    expect(normaliseRegistrationPlate('abc123')).toBe('ABC123');
  });

  it('treats blank / whitespace-only / null / undefined as a cleared field', () => {
    expect(normaliseRegistrationPlate('')).toBeNull();
    expect(normaliseRegistrationPlate('   ')).toBeNull();
    expect(normaliseRegistrationPlate(null)).toBeNull();
    expect(normaliseRegistrationPlate(undefined)).toBeNull();
  });

  it('is format-agnostic — imports / personalised plates pass unchanged (bar normalisation)', () => {
    expect(normaliseRegistrationPlate('gb-x9')).toBe('GB-X9');
    expect(normaliseRegistrationPlate('my ride')).toBe('MY RIDE');
  });

  it('parses a plate on add: normalised into the built document', () => {
    const parsed = parseAddVehicleInput({ ...validAdd, registrationPlate: '  abc 123 ' }, NOW);
    if (!parsed.ok) throw new Error('expected ok');
    expect(parsed.input.registrationPlate).toBe('ABC 123');
    const doc = buildVehicleDocument(parsed.input, 'u1', serverTimestamp);
    expect(doc.registrationPlate).toBe('ABC 123');
  });

  it('omitting the plate leaves it null on the document (not required)', () => {
    const parsed = parseAddVehicleInput(validAdd, NOW);
    if (!parsed.ok) throw new Error('expected ok');
    expect(buildVehicleDocument(parsed.input, 'u1', serverTimestamp).registrationPlate).toBeNull();
  });

  it('an explicit blank plate on update clears the stored value (null)', () => {
    const parsed = parseUpdateVehicleInput({ vehicleId: 'v1', registrationPlate: '   ' }, NOW);
    if (!parsed.ok) throw new Error('expected ok');
    const { update, changedFields } = buildVehicleUpdate(parsed.input, serverTimestamp);
    expect(changedFields).toContain('registrationPlate');
    expect(update.registrationPlate).toBeNull();
  });

  it('enforces the max length against the NORMALISED value', () => {
    // 12 chars after normalisation is fine; 13 is rejected.
    expect(parseAddVehicleInput({ ...validAdd, registrationPlate: 'ABCDEFGHIJKL' }, NOW).ok).toBe(
      true,
    );
    expect(parseAddVehicleInput({ ...validAdd, registrationPlate: 'ABCDEFGHIJKLM' }, NOW).ok).toBe(
      false,
    );
    // Padding/whitespace does NOT count — it collapses away before the length check.
    expect(parseAddVehicleInput({ ...validAdd, registrationPlate: '   ABC 123   ' }, NOW).ok).toBe(
      true,
    );
  });
});

describe('garage-core image path validation', () => {
  it('accepts only paths under the caller-and-vehicle prefix', () => {
    expect(vehicleImagePrefix('u1', 'v1')).toBe('vehicleImages/u1/v1/');
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/photo.jpg', 'u1', 'v1')).toBe(true);
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/', 'u1', 'v1')).toBe(false);
    // A whitespace-only image id is blank, not a genuine path.
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/   ', 'u1', 'v1')).toBe(false);
    // Exactly one segment below the prefix — storage rules cannot serve
    // nested paths (vehicleImages/{userId}/{vehicleId}/{imageId}).
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/subdir/photo.jpg', 'u1', 'v1')).toBe(false);
    expect(isValidVehicleImagePath('vehicleImages/u2/v1/photo.jpg', 'u1', 'v1')).toBe(false);
    expect(isValidVehicleImagePath('vehicleImages/u1/v2/photo.jpg', 'u1', 'v1')).toBe(false);
    expect(isValidVehicleImagePath('profileImages/u1/photo.jpg', 'u1', 'v1')).toBe(false);
  });
});

describe('garage-core document builders', () => {
  it('builds the vehicle document with null optionals and no image', () => {
    const parsed = parseAddVehicleInput(validAdd, NOW);
    if (!parsed.ok) throw new Error('expected ok');
    const docData = buildVehicleDocument(parsed.input, 'u1', serverTimestamp);
    expect(docData.userId).toBe('u1');
    expect(docData.engineDescription).toBeNull();
    expect(docData.color).toBeNull();
    expect(docData.imagePath).toBeNull();
    // New vehicles start with an empty gallery.
    expect(docData.photoPaths).toEqual([]);
    // New vehicles are never the main car until the owner marks one.
    expect(docData.isMainCar).toBe(false);
    expect(docData.createdAt).toBe('SERVER_TS');
  });

  it('routes partial updates and reports changed fields', () => {
    const parsed = parseUpdateVehicleInput(
      { vehicleId: 'v1', model: 'Absolut', imagePath: null },
      NOW,
    );
    if (!parsed.ok) throw new Error('expected ok');
    const { update, changedFields } = buildVehicleUpdate(parsed.input, serverTimestamp);
    expect(update.model).toBe('Absolut');
    expect(update.imagePath).toBeNull();
    expect(update.updatedAt).toBe('SERVER_TS');
    // A LEGACY free-text edit also clears the catalogue ids: text that no longer
    // matches the stored ids would silently corrupt the per-manufacturer counts,
    // so the vehicle drops out of the aggregate rather than lying inside it.
    expect(changedFields.sort()).toEqual([
      'catalogueVersion',
      'imagePath',
      'makeId',
      'model',
      'modelId',
    ]);
    expect(update.makeId).toBeNull();
    expect(update.modelId).toBeNull();
    expect(update.catalogueVersion).toBeNull();

    const empty = parseUpdateVehicleInput({ vehicleId: 'v1' }, NOW);
    if (!empty.ok) throw new Error('expected ok');
    expect(buildVehicleUpdate(empty.input, serverTimestamp).changedFields).toHaveLength(0);
  });
});

const PREFIX = 'vehicleImages/u1/v1/';
const p = (id: string) => `${PREFIX}${id}`;

describe('garage-core multi-photo input parsing', () => {
  it('parses addVehiclePhoto / removeVehiclePhoto and rejects malformed input', () => {
    expect(parseAddVehiclePhotoInput({ vehicleId: 'v1', photoPath: p('a.jpg') }).ok).toBe(true);
    expect(parseRemoveVehiclePhotoInput({ vehicleId: 'v1', photoPath: p('a.jpg') }).ok).toBe(true);
    // Missing/blank photoPath, a foreign extra field, and a bad vehicleId are rejected.
    expect(parseAddVehiclePhotoInput({ vehicleId: 'v1' }).ok).toBe(false);
    expect(parseAddVehiclePhotoInput({ vehicleId: 'v1', photoPath: '' }).ok).toBe(false);
    expect(parseAddVehiclePhotoInput({ vehicleId: 'v1', photoPath: p('a.jpg'), x: 1 }).ok).toBe(
      false,
    );
    expect(
      parseAddVehiclePhotoInput({ vehicleId: 'vehicles/other', photoPath: p('a.jpg') }).ok,
    ).toBe(false);
    // Prefix validity is NOT enforced here (needs uid+vehicleId at the callable):
    // a well-formed but foreign-looking string still parses.
    expect(parseAddVehiclePhotoInput({ vehicleId: 'v1', photoPath: 'anything' }).ok).toBe(true);
  });

  it('parses reorderVehiclePhotos and bounds the array', () => {
    expect(
      parseReorderVehiclePhotosInput({ vehicleId: 'v1', orderedPaths: [p('a.jpg'), p('b.jpg')] })
        .ok,
    ).toBe(true);
    // Empty array, over-cap array, and non-string entries are rejected.
    expect(parseReorderVehiclePhotosInput({ vehicleId: 'v1', orderedPaths: [] }).ok).toBe(false);
    expect(
      parseReorderVehiclePhotosInput({
        vehicleId: 'v1',
        orderedPaths: Array.from({ length: MAX_VEHICLE_PHOTOS + 1 }, (_, i) => p(`${i}.jpg`)),
      }).ok,
    ).toBe(false);
    expect(parseReorderVehiclePhotosInput({ vehicleId: 'v1', orderedPaths: [1] }).ok).toBe(false);
  });
});

describe('garage-core photo gallery logic', () => {
  it('reads a legacy single-photo doc as a one-element gallery', () => {
    expect(readExistingPhotoPaths({ imagePath: p('a.jpg') })).toEqual([p('a.jpg')]);
    expect(readExistingPhotoPaths({ imagePath: null })).toEqual([]);
    expect(readExistingPhotoPaths({})).toEqual([]);
  });

  it('reads a photoPaths array verbatim, dropping blanks/non-strings', () => {
    expect(readExistingPhotoPaths({ photoPaths: [p('a.jpg'), p('b.jpg')] })).toEqual([
      p('a.jpg'),
      p('b.jpg'),
    ]);
    // A present (even empty) array wins over the legacy imagePath fallback.
    expect(readExistingPhotoPaths({ photoPaths: [], imagePath: p('a.jpg') })).toEqual([]);
    // Blanks include whitespace-only strings — they must not survive as paths.
    expect(readExistingPhotoPaths({ photoPaths: ['', '   ', 3, p('a.jpg')] })).toEqual([
      p('a.jpg'),
    ]);
    // A whitespace-only legacy imagePath is also treated as no photo.
    expect(readExistingPhotoPaths({ imagePath: '   ' })).toEqual([]);
  });

  it('appends a photo, rejecting duplicates and enforcing the cap', () => {
    expect(appendPhotoPath([], p('a.jpg'))).toEqual({ ok: true, paths: [p('a.jpg')] });
    expect(appendPhotoPath([p('a.jpg')], p('a.jpg'))).toEqual({ ok: false, error: 'duplicate' });

    const full = Array.from({ length: MAX_VEHICLE_PHOTOS }, (_, i) => p(`${i}.jpg`));
    expect(appendPhotoPath(full, p('extra.jpg'))).toEqual({ ok: false, error: 'cap' });
    // One below the cap still appends.
    expect(appendPhotoPath(full.slice(0, -1), p('extra.jpg')).ok).toBe(true);
  });

  it('removes a photo and promotes the next to cover', () => {
    // Removing the cover (index 0) promotes b.jpg to the new cover.
    const removedCover = removePhotoPath([p('a.jpg'), p('b.jpg'), p('c.jpg')], p('a.jpg'));
    expect(removedCover.found).toBe(true);
    expect(removedCover.paths).toEqual([p('b.jpg'), p('c.jpg')]);
    expect(coverPhotoPath(removedCover.paths)).toBe(p('b.jpg'));

    // Removing a non-cover keeps the cover, preserves order.
    expect(removePhotoPath([p('a.jpg'), p('b.jpg')], p('b.jpg')).paths).toEqual([p('a.jpg')]);
    // Removing the last photo empties the gallery (cover null).
    expect(coverPhotoPath(removePhotoPath([p('a.jpg')], p('a.jpg')).paths)).toBeNull();
    // A path not on the vehicle is reported as not found.
    expect(removePhotoPath([p('a.jpg')], p('z.jpg')).found).toBe(false);
  });

  it('validates a reorder as a permutation of the existing set', () => {
    const existing = [p('a.jpg'), p('b.jpg'), p('c.jpg')];
    expect(isPhotoPermutation(existing, [p('c.jpg'), p('a.jpg'), p('b.jpg')])).toBe(true);
    // Wrong count, missing member, or an extra member all fail.
    expect(isPhotoPermutation(existing, [p('a.jpg'), p('b.jpg')])).toBe(false);
    expect(isPhotoPermutation(existing, [p('a.jpg'), p('b.jpg'), p('z.jpg')])).toBe(false);
    expect(isPhotoPermutation(existing, [...existing, p('a.jpg')])).toBe(false);
    // A duplicate that pretends to be a reorder is rejected (multiset compare).
    expect(isPhotoPermutation([p('a.jpg'), p('b.jpg')], [p('a.jpg'), p('a.jpg')])).toBe(false);
  });

  it('reconciles the cover mirror set through updateVehicle imagePath', () => {
    // Null clears the whole gallery.
    expect(reconcileCoverPhotoPaths([p('a.jpg'), p('b.jpg')], null)).toEqual([]);
    // A cover already present is promoted to the front (reorder).
    expect(reconcileCoverPhotoPaths([p('a.jpg'), p('b.jpg'), p('c.jpg')], p('c.jpg'))).toEqual([
      p('c.jpg'),
      p('a.jpg'),
      p('b.jpg'),
    ]);
    // A brand-new cover replaces the old cover, preserving the rest.
    expect(reconcileCoverPhotoPaths([p('a.jpg'), p('b.jpg')], p('new.jpg'))).toEqual([
      p('new.jpg'),
      p('b.jpg'),
    ]);
    // First photo on an empty gallery.
    expect(reconcileCoverPhotoPaths([], p('a.jpg'))).toEqual([p('a.jpg')]);
  });
});
