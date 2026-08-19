/**
 * Unit tests for the live WAVE pure logic (wave-core.ts). No emulator.
 *
 * These pin the SERVER-AUTHORITATIVE anti-spam invariant off the transaction:
 * the single cooldown window, the boundary of the within-cooldown predicate and
 * its remaining-time companion, the fixed reach + retention, the payload builder,
 * and the input parser (which accepts only an optional well-formed clientId and
 * rejects a client-supplied position/radius before any read).
 */

import { describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  SEND_WAVE_EXPECTED,
  WAVE_COOLDOWN_MS,
  WAVE_RADIUS_METERS,
  WAVE_RETENTION_MINUTES,
  buildWaveDocument,
  isWithinWaveCooldown,
  parseSendWaveInput,
  waveCooldownExpiry,
  waveCooldownRemainingMs,
  waveExpiry,
} from './wave-core';

describe('wave cooldown window', () => {
  it('is a single per-user window in the 30–60s band the owner asked for', () => {
    expect(WAVE_COOLDOWN_MS).toBe(45_000);
  });

  it('a user who has never waved is never in cooldown', () => {
    expect(isWithinWaveCooldown(null, 1_000_000)).toBe(false);
    expect(waveCooldownRemainingMs(null, 1_000_000)).toBe(0);
  });

  it('is within cooldown strictly inside the window, and clear at/after the boundary', () => {
    const last = 1_000_000;
    // Just after the send: fully throttled.
    expect(isWithinWaveCooldown(last, last + 1)).toBe(true);
    // One ms before the window closes: still throttled.
    expect(isWithinWaveCooldown(last, last + WAVE_COOLDOWN_MS - 1)).toBe(true);
    // Exactly at the boundary: allowed (predicate is strict `<`).
    expect(isWithinWaveCooldown(last, last + WAVE_COOLDOWN_MS)).toBe(false);
    // After the boundary: allowed.
    expect(isWithinWaveCooldown(last, last + WAVE_COOLDOWN_MS + 5)).toBe(false);
  });

  it('remaining time counts down to zero across the window', () => {
    const last = 2_000_000;
    expect(waveCooldownRemainingMs(last, last)).toBe(WAVE_COOLDOWN_MS);
    expect(waveCooldownRemainingMs(last, last + 10_000)).toBe(WAVE_COOLDOWN_MS - 10_000);
    expect(waveCooldownRemainingMs(last, last + WAVE_COOLDOWN_MS)).toBe(0);
    // Past the window never goes negative.
    expect(waveCooldownRemainingMs(last, last + WAVE_COOLDOWN_MS + 5_000)).toBe(0);
  });

  it('a corrupt (non-finite) last-sent time never wedges the user out of waving', () => {
    expect(isWithinWaveCooldown(Number.NaN, 1_000)).toBe(false);
    expect(isWithinWaveCooldown(Number.POSITIVE_INFINITY, 1_000)).toBe(false);
    expect(waveCooldownRemainingMs(Number.NaN, 1_000)).toBe(0);
  });
});

describe('wave reach + retention', () => {
  it('reach matches the live-discovery default so icon-presence and reach agree', () => {
    // Same radius the Android nearby poll / listNearby default use (15 km), so a
    // wave reaches exactly the sharers the wave icon is derived from.
    expect(WAVE_RADIUS_METERS).toBe(15_000);
  });

  it('delivered waves are retained for a few minutes; the cooldown doc far longer', () => {
    const now = new Date('2026-08-19T10:00:00.000Z');
    const waveMs = waveExpiry(now).toMillis() - now.getTime();
    expect(waveMs).toBe(WAVE_RETENTION_MINUTES * 60_000);
    const cooldownMs = waveCooldownExpiry(now).toMillis() - now.getTime();
    // The cooldown doc must comfortably outlive the cooldown window.
    expect(cooldownMs).toBeGreaterThan(WAVE_COOLDOWN_MS);
    expect(cooldownMs).toBe(60 * 60_000);
  });
});

describe('wave document payload', () => {
  it('denormalizes the sender and carries the shared wave id + timestamps', () => {
    const createdAt = Timestamp.fromMillis(1_700_000_000_000);
    const expireAt = Timestamp.fromMillis(1_700_000_300_000);
    const doc = buildWaveDocument({
      waveId: 'wave-abc',
      senderUid: 'sender-1',
      senderDisplayName: 'Anna',
      createdAt,
      expireAt,
    });
    expect(doc).toEqual({
      waveId: 'wave-abc',
      senderUid: 'sender-1',
      senderDisplayName: 'Anna',
      createdAt,
      expireAt,
    });
  });

  it('keeps a null display name (a missing public name renders as an anonymous wave)', () => {
    const ts = Timestamp.fromMillis(1_700_000_000_000);
    const doc = buildWaveDocument({
      waveId: 'w',
      senderUid: 's',
      senderDisplayName: null,
      createdAt: ts,
      expireAt: ts,
    });
    expect(doc.senderDisplayName).toBeNull();
  });
});

describe('sendWave input parsing', () => {
  it('accepts empty input (the common case — the server reads the position)', () => {
    const parsed = parseSendWaveInput({});
    expect(parsed.ok).toBe(true);
    if (parsed.ok) expect(parsed.input.clientId).toBeUndefined();
  });

  it('accepts a null/undefined body', () => {
    expect(parseSendWaveInput(undefined).ok).toBe(true);
    expect(parseSendWaveInput(null).ok).toBe(true);
  });

  it('accepts a well-formed idempotency clientId', () => {
    const parsed = parseSendWaveInput({ clientId: 'abc-123_XYZ' });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) expect(parsed.input.clientId).toBe('abc-123_XYZ');
  });

  it('rejects a client-supplied position or radius (strict schema — cannot widen its blast radius)', () => {
    const withCoords = parseSendWaveInput({ latitude: 59.9, longitude: 17.6 });
    expect(withCoords.ok).toBe(false);
    if (!withCoords.ok) expect(withCoords.message).toBe(SEND_WAVE_EXPECTED);
    expect(parseSendWaveInput({ radiusMeters: 50_000 }).ok).toBe(false);
  });

  it('rejects a malformed clientId (bad chars / too long)', () => {
    expect(parseSendWaveInput({ clientId: 'has space' }).ok).toBe(false);
    expect(parseSendWaveInput({ clientId: 'a'.repeat(65) }).ok).toBe(false);
    expect(parseSendWaveInput({ clientId: '' }).ok).toBe(false);
  });
});
