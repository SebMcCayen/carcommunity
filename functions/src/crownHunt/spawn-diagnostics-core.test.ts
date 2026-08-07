/**
 * Unit tests for the db-free helpers behind crownHunt.spawnDiagnostics.
 */

import { describe, expect, it } from 'vitest';
import { classifyDiagnosticCell, nextScheduledSpawnRun } from './spawn-diagnostics-core';

const TEN_MIN = 10 * 60 * 1000;

describe('nextScheduledSpawnRun', () => {
  it('returns the next epoch-aligned interval boundary', () => {
    // 12:03:20 → next 10-min boundary is 12:10:00.
    const now = new Date('2026-08-07T12:03:20.000Z');
    expect(nextScheduledSpawnRun(now, TEN_MIN).toISOString()).toBe('2026-08-07T12:10:00.000Z');
  });

  it('advances a full interval when now sits exactly on a boundary', () => {
    // The run for 12:10:00 is firing now, so the NEXT is 12:20:00 — never 0.
    const now = new Date('2026-08-07T12:10:00.000Z');
    expect(nextScheduledSpawnRun(now, TEN_MIN).toISOString()).toBe('2026-08-07T12:20:00.000Z');
  });

  it('is always strictly in the future', () => {
    const now = new Date('2026-08-07T12:09:59.999Z');
    expect(nextScheduledSpawnRun(now, TEN_MIN).getTime()).toBeGreaterThan(now.getTime());
  });
});

describe('classifyDiagnosticCell', () => {
  it('below the activity floor → never spawns (target 0)', () => {
    expect(classifyDiagnosticCell({ target: 0, liveCount: 0, poiCount: 5 })).toEqual({
      deficit: 0,
      reason: 'below_activity_floor',
      eligible: false,
    });
  });

  it('below target but no POI anchor → nothing to place on', () => {
    expect(classifyDiagnosticCell({ target: 3, liveCount: 1, poiCount: 0 })).toEqual({
      deficit: 2,
      reason: 'no_pois_in_cell',
      eligible: false,
    });
  });

  it('already at (or over) target → nothing to add', () => {
    expect(classifyDiagnosticCell({ target: 2, liveCount: 2, poiCount: 4 })).toEqual({
      deficit: 0,
      reason: 'at_target',
      eligible: false,
    });
    expect(classifyDiagnosticCell({ target: 2, liveCount: 5, poiCount: 4 }).reason).toBe(
      'at_target',
    );
  });

  it('below target with a POI anchor → a real spawn candidate', () => {
    expect(classifyDiagnosticCell({ target: 4, liveCount: 1, poiCount: 2 })).toEqual({
      deficit: 3,
      reason: 'would_spawn',
      eligible: true,
    });
  });

  it('checks the activity floor before the POI gate', () => {
    // No activity AND no POIs — the floor is the reported reason, not the POIs.
    expect(classifyDiagnosticCell({ target: 0, liveCount: 0, poiCount: 0 }).reason).toBe(
      'below_activity_floor',
    );
  });
});
