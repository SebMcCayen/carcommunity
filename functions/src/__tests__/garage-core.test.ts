/**
 * Unit tests for the garage pure logic (garage-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildVehicleDocument,
  buildVehicleUpdate,
  isValidVehicleImagePath,
  maxModelYear,
  parseAddVehicleInput,
  parseDeleteVehicleInput,
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
