/**
 * Unit tests for the garage pure logic (garage-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  LEGACY_VEHICLE_POWERTRAINS,
  SELECTABLE_VEHICLE_POWERTRAINS,
  VEHICLE_POWERTRAINS,
  buildVehicleDocument,
  buildVehicleUpdate,
  isValidVehicleImagePath,
  maxModelYear,
  parseAddVehicleInput,
  parseDeleteVehicleInput,
  parseSetMainVehicleInput,
  parseUpdateVehicleInput,
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

  it('makes registration plates and VIN unrepresentable (strict schema)', () => {
    expect(
      parseAddVehicleInput({ ...validAdd, registrationPlate: 'ABC123' }, NOW).ok,
    ).toBe(false);
    expect(parseAddVehicleInput({ ...validAdd, vin: 'YV1abc' }, NOW).ok).toBe(false);
    expect(
      parseUpdateVehicleInput({ vehicleId: 'v1', registrationPlate: 'ABC123' }, NOW).ok,
    ).toBe(false);
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

describe('garage-core image path validation', () => {
  it('accepts only paths under the caller-and-vehicle prefix', () => {
    expect(vehicleImagePrefix('u1', 'v1')).toBe('vehicleImages/u1/v1/');
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/photo.jpg', 'u1', 'v1')).toBe(true);
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/', 'u1', 'v1')).toBe(false);
    // Exactly one segment below the prefix — storage rules cannot serve
    // nested paths (vehicleImages/{userId}/{vehicleId}/{imageId}).
    expect(isValidVehicleImagePath('vehicleImages/u1/v1/subdir/photo.jpg', 'u1', 'v1')).toBe(
      false,
    );
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
    expect(changedFields.sort()).toEqual(['imagePath', 'model']);

    const empty = parseUpdateVehicleInput({ vehicleId: 'v1' }, NOW);
    if (!empty.ok) throw new Error('expected ok');
    expect(buildVehicleUpdate(empty.input, serverTimestamp).changedFields).toHaveLength(0);
  });
});
