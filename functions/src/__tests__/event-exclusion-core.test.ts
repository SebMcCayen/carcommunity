/**
 * Pure unit tests for the trap event-exclusion rule (anti-griefing).
 *
 * These exercise ONLY the time-window + 300 m distance maths in
 * event-exclusion-core.ts — no Firestore, no emulator. The caller
 * (deployPerk.ts) is responsible for restricting candidates to
 * published/completed events with valid coordinates; the caller-side filtering
 * (draft/cancelled ignored, missing coords skipped) is covered by the
 * crownHunt-pvp emulator test.
 */

import { describe, expect, it } from 'vitest';
import { isTrapBlockedByEvents, type TrapExclusionEvent } from '../crownHunt/event-exclusion-core';
import {
  EVENT_TRAP_BLOCK_AFTER_END_MS,
  EVENT_TRAP_BLOCK_BEFORE_START_MS,
} from '../crownHunt/perks-core';

// A meet at (57.0, 12.0). ~0.002 deg latitude ≈ 222 m (inside 300 m); ~0.004
// deg ≈ 445 m (outside). Latitude offsets avoid the longitude cos(lat) scaling.
const EVENT_LAT = 57.0;
const EVENT_LNG = 12.0;
const INSIDE_LAT = 57.002; // ~222 m north — inside the 300 m radius
const OUTSIDE_LAT = 57.004; // ~445 m north — outside the 300 m radius

const STARTS_AT = Date.UTC(2026, 7, 28, 12, 0, 0); // 2026-08-28T12:00:00Z
const TWO_HOURS = 2 * 60 * 60 * 1000;
const ONE_MINUTE = 60 * 1000;

function event(overrides: Partial<TrapExclusionEvent> = {}): TrapExclusionEvent {
  return {
    startsAtMs: STARTS_AT,
    endsAtMs: STARTS_AT + TWO_HOURS,
    latitude: EVENT_LAT,
    longitude: EVENT_LNG,
    ...overrides,
  };
}

describe('isTrapBlockedByEvents', () => {
  const duringWindow = STARTS_AT + 60 * 60 * 1000; // 1 h into the event

  it('blocks a trap inside 300 m during the event', () => {
    expect(isTrapBlockedByEvents([event()], INSIDE_LAT, EVENT_LNG, duringWindow)).toBe(true);
  });

  it('allows a trap outside 300 m even during the event', () => {
    expect(isTrapBlockedByEvents([event()], OUTSIDE_LAT, EVENT_LNG, duringWindow)).toBe(false);
  });

  it('blocks a trap on the exact event coordinate (distance 0)', () => {
    expect(isTrapBlockedByEvents([event()], EVENT_LAT, EVENT_LNG, duringWindow)).toBe(true);
  });

  it('allows a trap just BEFORE the −8 h pre-window opens', () => {
    const justBeforeOpen = STARTS_AT - EVENT_TRAP_BLOCK_BEFORE_START_MS - ONE_MINUTE;
    expect(isTrapBlockedByEvents([event()], INSIDE_LAT, EVENT_LNG, justBeforeOpen)).toBe(false);
  });

  it('blocks a trap just AFTER the −8 h pre-window opens', () => {
    const justAfterOpen = STARTS_AT - EVENT_TRAP_BLOCK_BEFORE_START_MS + ONE_MINUTE;
    expect(isTrapBlockedByEvents([event()], INSIDE_LAT, EVENT_LNG, justAfterOpen)).toBe(true);
  });

  it('blocks a trap just BEFORE the +3 h post-window closes', () => {
    const justBeforeClose = STARTS_AT + TWO_HOURS + EVENT_TRAP_BLOCK_AFTER_END_MS - ONE_MINUTE;
    expect(isTrapBlockedByEvents([event()], INSIDE_LAT, EVENT_LNG, justBeforeClose)).toBe(true);
  });

  it('allows a trap just AFTER the +3 h post-window closes', () => {
    const justAfterClose = STARTS_AT + TWO_HOURS + EVENT_TRAP_BLOCK_AFTER_END_MS + ONE_MINUTE;
    expect(isTrapBlockedByEvents([event()], INSIDE_LAT, EVENT_LNG, justAfterClose)).toBe(false);
  });

  it('uses startsAt as the effective end when endsAt is missing', () => {
    const noEnd = event({ endsAtMs: null });
    // Window closes 3 h after startsAt (not after startsAt + 2 h).
    const withinNoEnd = STARTS_AT + EVENT_TRAP_BLOCK_AFTER_END_MS - ONE_MINUTE;
    const afterNoEnd = STARTS_AT + EVENT_TRAP_BLOCK_AFTER_END_MS + ONE_MINUTE;
    expect(isTrapBlockedByEvents([noEnd], INSIDE_LAT, EVENT_LNG, withinNoEnd)).toBe(true);
    expect(isTrapBlockedByEvents([noEnd], INSIDE_LAT, EVENT_LNG, afterNoEnd)).toBe(false);
  });

  it('returns false for an empty candidate list', () => {
    expect(isTrapBlockedByEvents([], INSIDE_LAT, EVENT_LNG, duringWindow)).toBe(false);
  });

  it('blocks when ANY one of several candidates matches', () => {
    const farInTime = event({ startsAtMs: STARTS_AT + 30 * 24 * 60 * 60 * 1000, endsAtMs: null });
    const matching = event();
    expect(
      isTrapBlockedByEvents([farInTime, matching], INSIDE_LAT, EVENT_LNG, duringWindow),
    ).toBe(true);
  });

  it('does not block when a nearby event is outside its time window', () => {
    // Same spot, but the only candidate is a month away in time.
    const future = event({
      startsAtMs: STARTS_AT + 30 * 24 * 60 * 60 * 1000,
      endsAtMs: STARTS_AT + 30 * 24 * 60 * 60 * 1000 + TWO_HOURS,
    });
    expect(isTrapBlockedByEvents([future], INSIDE_LAT, EVENT_LNG, duringWindow)).toBe(false);
  });
});
