/**
 * Unit tests for the live location pure logic (live-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  buildLatestNode,
  buildSession,
  guardPositionFreshness,
  isLatestStale,
  isSessionActive,
  parseStartSessionInput,
  parseStopSessionInput,
  parseUpdatePositionInput,
  toLiveMainCar,
} from '../live/live-core';

const NOW = new Date('2026-07-05T12:00:00Z');

describe('live-core inputs', () => {
  it('validates durations, coordinates, and stop reasons', () => {
    expect(parseStartSessionInput({ duration: '2h' }).ok).toBe(true);
    expect(parseStartSessionInput({ duration: '8h' }).ok).toBe(false);
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
