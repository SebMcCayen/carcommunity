/**
 * Unit tests for the live location pure logic (live-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  LIVE_SESSION_DURATIONS,
  buildLatestNode,
  buildSession,
  guardPositionFreshness,
  isLatestStale,
  isSessionActive,
  parseStartSessionInput,
  parseStopSessionInput,
  parseUpdatePositionInput,
  pickSessionVehicleData,
  toLiveMainCar,
} from '../live/live-core';

const NOW = new Date('2026-07-05T12:00:00Z');

describe('live-core inputs', () => {
  it('validates durations, coordinates, and stop reasons', () => {
    expect(parseStartSessionInput({ duration: '2h' }).ok).toBe(true);
    // '6h' is the window the current client always starts (the 6h hard cap).
    expect(parseStartSessionInput({ duration: '6h' }).ok).toBe(true);
    expect(parseStartSessionInput({ duration: '8h' }).ok).toBe(false);
    // The start schema's accepted keys are DERIVED from LIVE_SESSION_DURATIONS
    // (single source of truth), so every map key must parse and nothing else.
    for (const key of Object.keys(LIVE_SESSION_DURATIONS)) {
      expect(parseStartSessionInput({ duration: key }).ok).toBe(true);
    }
    expect(parseStartSessionInput({ duration: '3h' }).ok).toBe(false);
    expect(
      parseUpdatePositionInput({
        coordinate: { latitude: 59.33, longitude: 18.07, recordedAt: NOW.toISOString() },
      }).ok,
    ).toBe(true);
    expect(
      parseUpdatePositionInput({
        coordinate: { latitude: 91, longitude: 18.07, recordedAt: NOW.toISOString() },
      }).ok,
    ).toBe(false);
    expect(
      parseUpdatePositionInput({
        coordinate: { latitude: 59.33, longitude: 18.07, recordedAt: 'yesterday' },
      }).ok,
    ).toBe(false);
    expect(parseStopSessionInput({}).ok).toBe(true);
    expect(parseStopSessionInput({ reason: 'hide_me_now' }).ok).toBe(true);
    expect(parseStopSessionInput({ reason: 'panic' }).ok).toBe(false);
  });

  it('accepts an optional vehicleId on start, rejects a blank one', () => {
    // The "Start driving" picker sends the chosen car; omitting it is valid
    // (falls back to the main car server-side).
    const withVehicle = parseStartSessionInput({ duration: '6h', vehicleId: 'veh-123' });
    expect(withVehicle.ok).toBe(true);
    expect(withVehicle.ok && withVehicle.input.vehicleId).toBe('veh-123');
    expect(parseStartSessionInput({ duration: '6h' }).ok).toBe(true);
    // A blank id is meaningless — reject rather than silently treat as "no car".
    expect(parseStartSessionInput({ duration: '6h', vehicleId: '' }).ok).toBe(false);
    // Strict schema still rejects stray fields alongside the new one.
    expect(parseStartSessionInput({ duration: '6h', vehicleId: 'v', bogus: 1 }).ok).toBe(false);
  });
});

describe('live-core pickSessionVehicleData (Start-driving car selection)', () => {
  const car = (id: string, extra: Record<string, unknown> = {}) => ({
    id,
    data: { make: 'Volvo', model: '240', modelYear: 1989, ...extra },
  });

  it('returns null when the caller owns no cars', () => {
    expect(pickSessionVehicleData([])).toBeNull();
    expect(pickSessionVehicleData([], 'veh-1')).toBeNull();
  });

  it('honours the explicitly chosen vehicleId when still owned', () => {
    const vehicles = [car('a'), car('b', { isMainCar: true }), car('c')];
    expect(pickSessionVehicleData(vehicles, 'c')).toBe(vehicles[2]!.data);
  });

  it('falls back to the main car when no id is chosen', () => {
    const vehicles = [car('a'), car('b', { isMainCar: true }), car('c')];
    expect(pickSessionVehicleData(vehicles)).toBe(vehicles[1]!.data);
  });

  it('falls back to the main car when the chosen id is no longer owned', () => {
    const vehicles = [car('a'), car('b', { isMainCar: true })];
    expect(pickSessionVehicleData(vehicles, 'deleted')).toBe(vehicles[1]!.data);
  });

  it('falls back to the first car when none is flagged main', () => {
    const vehicles = [car('a'), car('b'), car('c')];
    expect(pickSessionVehicleData(vehicles)).toBe(vehicles[0]!.data);
    expect(pickSessionVehicleData(vehicles, 'missing')).toBe(vehicles[0]!.data);
  });

  it('flows a chosen car through toLiveMainCar without leaking the plate', () => {
    const vehicles = [
      car('a', { isMainCar: true }),
      car('b', { make: 'Saab', model: '900', modelYear: 1993, registrationPlate: 'ABC 123' }),
    ];
    const projected = toLiveMainCar(pickSessionVehicleData(vehicles, 'b'));
    expect(projected).toEqual({ make: 'Saab', model: '900', modelYear: 1993, imagePath: null });
    expect(projected).not.toHaveProperty('registrationPlate');
  });
});

describe('live-core freshness (contract 60s threshold)', () => {
  it('accepts fresh, rejects stale and far-future positions', () => {
    const at = (secondsAgo: number) =>
      new Date(NOW.getTime() - secondsAgo * 1000).toISOString();
    expect(guardPositionFreshness(at(5), NOW).ok).toBe(true);
    expect(guardPositionFreshness(at(59), NOW).ok).toBe(true);
    expect(guardPositionFreshness(at(61), NOW).ok).toBe(false);
    expect(guardPositionFreshness(at(-10), NOW).ok).toBe(true); // small skew allowed
    expect(guardPositionFreshness(at(-60), NOW).ok).toBe(false);
  });
});

describe('live-core toLiveMainCar', () => {
  it('projects only the display-safe car fields', () => {
    expect(
      toLiveMainCar({
        userId: 'u1',
        make: 'Volvo',
        model: '242',
        modelYear: 1980,
        powertrain: 'petrol',
        imagePath: 'vehicleImages/u1/v1/photo.jpg',
        isMainCar: true,
      }),
    ).toEqual({ make: 'Volvo', model: '242', modelYear: 1980, imagePath: 'vehicleImages/u1/v1/photo.jpg' });
  });

  it('never carries registrationPlate onto the live marker', () => {
    // registrationPlate is a deliberately PUBLIC field on vehicles/{id}, so the
    // schema no longer prevents it reaching here — only this projection does.
    // A live marker pairs the car with a real-time position, which is a far
    // stronger disclosure than a static car profile, so the plate must stay off.
    const projected = toLiveMainCar({
      make: 'Volvo',
      model: '242',
      modelYear: 1980,
      registrationPlate: 'ABC 123',
      imagePath: null,
    });
    expect(projected).not.toBeNull();
    expect(Object.keys(projected!).sort()).toEqual(['imagePath', 'make', 'model', 'modelYear']);
    expect(JSON.stringify(projected)).not.toContain('ABC 123');
  });

  it('defaults a missing image to null and rejects malformed/absent docs', () => {
    expect(toLiveMainCar({ make: 'Saab', model: '900', modelYear: 1993 })).toEqual({
      make: 'Saab',
      model: '900',
      modelYear: 1993,
      imagePath: null,
    });
    expect(toLiveMainCar(null)).toBeNull();
    expect(toLiveMainCar(undefined)).toBeNull();
    // Malformed: missing model / non-numeric year.
    expect(toLiveMainCar({ make: 'Saab', modelYear: 1993 })).toBeNull();
    expect(toLiveMainCar({ make: 'Saab', model: '900', modelYear: '1993' })).toBeNull();
  });

  it('rejects non-finite, non-integer, or out-of-range model years (RTDB-unsafe)', () => {
    const base = { make: 'Saab', model: '900' };
    // NaN / Infinity are `typeof number` but can't be written to RTDB.
    expect(toLiveMainCar({ ...base, modelYear: NaN }, NOW)).toBeNull();
    expect(toLiveMainCar({ ...base, modelYear: Infinity }, NOW)).toBeNull();
    expect(toLiveMainCar({ ...base, modelYear: -Infinity }, NOW)).toBeNull();
    // Non-integer years (Android reads modelYear as a Long).
    expect(toLiveMainCar({ ...base, modelYear: 1993.5 }, NOW)).toBeNull();
    // Out of the garage-validation bounds (MIN_MODEL_YEAR..now+2).
    expect(toLiveMainCar({ ...base, modelYear: 1800 }, NOW)).toBeNull();
    expect(toLiveMainCar({ ...base, modelYear: NOW.getFullYear() + 3 }, NOW)).toBeNull();
    // A sane finite integer within bounds still projects.
    expect(toLiveMainCar({ ...base, modelYear: 1993 }, NOW)).toEqual({
      make: 'Saab',
      model: '900',
      modelYear: 1993,
      imagePath: null,
    });
  });
});

describe('live-core session lifecycle', () => {
  it('builds sessions with the chosen duration and detects expiry', () => {
    const session = buildSession('s1', '2h', NOW, 'Seb');
    expect(session.status).toBe('active');
    expect(session.expiresAt).toBe('2026-07-05T14:00:00.000Z');
    expect(isSessionActive(session, NOW)).toBe(true);
    expect(isSessionActive(session, new Date('2026-07-05T14:00:01Z'))).toBe(false);
    expect(isSessionActive({ ...session, status: 'stopped' }, NOW)).toBe(false);
    expect(isSessionActive(null, NOW)).toBe(false);
  });

  it('builds lean marker nodes with denormalized display data', () => {
    const session = buildSession('s1', '1h', NOW, 'Seb');
    const node = buildLatestNode(
      { latitude: 59.33, longitude: 18.07, recordedAt: NOW.toISOString() },
      session,
    );
    expect(node).toEqual({
      latitude: 59.33,
      longitude: 18.07,
      accuracyMeters: null,
      headingDegrees: null,
      speedMetersPerSecond: null,
      recordedAt: NOW.toISOString(),
      sessionId: 's1',
      expiresAt: session.expiresAt,
      displayName: 'Seb',
      mainCar: null,
    });
  });

  it('denormalizes the main car onto the session and marker node', () => {
    const mainCar = {
      make: 'Volvo',
      model: '242',
      modelYear: 1980,
      imagePath: 'vehicleImages/u1/v1/photo.jpg',
    };
    const session = buildSession('s1', '1h', NOW, 'Seb', mainCar);
    expect(session.mainCar).toEqual(mainCar);
    const node = buildLatestNode(
      { latitude: 59.33, longitude: 18.07, recordedAt: NOW.toISOString() },
      session,
    );
    expect(node.mainCar).toEqual(mainCar);
  });

  it('detects silent-stale markers numerically (non-canonical ISO safe)', () => {
    expect(isLatestStale('2026-07-05T11:44:59.000Z', NOW)).toBe(true);
    expect(isLatestStale('2026-07-05T11:45:01.000Z', NOW)).toBe(false);
    // No milliseconds and offset forms still compare correctly.
    expect(isLatestStale('2026-07-05T11:40:00Z', NOW)).toBe(true);
    expect(isLatestStale('2026-07-05T13:40:00+02:00', NOW)).toBe(true);
    expect(isLatestStale('garbage', NOW)).toBe(false);
  });
});
