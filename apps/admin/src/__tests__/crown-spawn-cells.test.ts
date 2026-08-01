import { describe, it, expect, vi } from 'vitest';

// The crown-hunt barrel pulls in the Firestore/callable data layer, which would
// otherwise eagerly initialise the Firebase app (and demand VITE_FIREBASE_*
// env). These pure-logic tests never touch the network, so stub those seams —
// same approach as audit-log.test.ts et al.
vi.mock('../lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (_db: unknown, path: string) => ({ path }),
  query: (target: unknown, ...constraints: unknown[]) => ({ target, constraints }),
  orderBy: (field: string, direction: string) => ({ type: 'orderBy', field, direction }),
  limit: (n: number) => ({ type: 'limit', n }),
  getDocs: vi.fn(),
}));

import {
  CROWN_CELL_DEGREES,
  cellKeyForCoords,
  parseSpawnCellKey,
  spawnCellBounds,
  spawnCellCenter,
  formatCellCenter,
  spawnCellState,
  toSpawnCellSummary,
} from '@/features/crown-hunt';

describe('cellKeyForCoords', () => {
  it('floor-divides both axes by the 0.01° cell size', () => {
    // 57.4874 / 0.01 = 5748.74 -> 5748 ; 12.0761 / 0.01 = 1207.61 -> 1207
    expect(cellKeyForCoords(57.4874, 12.0761)).toBe('5748_1207');
  });

  it('floors toward negative infinity for southern/western coordinates', () => {
    expect(cellKeyForCoords(-0.005, -0.005)).toBe('-1_-1');
  });

  it('clamps out-of-range coordinates onto the globe', () => {
    expect(cellKeyForCoords(999, 999)).toBe(`${Math.round(90 / CROWN_CELL_DEGREES)}_${Math.round(180 / CROWN_CELL_DEGREES)}`);
  });
});

describe('parseSpawnCellKey', () => {
  it('parses a well-formed key', () => {
    expect(parseSpawnCellKey('5748_1207')).toEqual({ latIdx: 5748, lonIdx: 1207 });
  });

  it('parses negative indices', () => {
    expect(parseSpawnCellKey('-1_-1')).toEqual({ latIdx: -1, lonIdx: -1 });
  });

  it('trims surrounding whitespace', () => {
    expect(parseSpawnCellKey('  10_20  ')).toEqual({ latIdx: 10, lonIdx: 20 });
  });

  it('rejects malformed keys', () => {
    expect(parseSpawnCellKey('abc')).toBeNull();
    expect(parseSpawnCellKey('10')).toBeNull();
    expect(parseSpawnCellKey('10_')).toBeNull();
    expect(parseSpawnCellKey('10_20_30')).toBeNull();
  });

  it('rejects keys off the globe (range check mirrors the backend)', () => {
    // latitude index > 9000 is beyond 90°N, which the callable would reject.
    expect(parseSpawnCellKey('50000_0')).toBeNull();
    expect(parseSpawnCellKey('0_50000')).toBeNull();
  });
});

describe('spawnCellBounds / spawnCellCenter', () => {
  it('returns the [min,max) box for a cell', () => {
    const bounds = spawnCellBounds('5748_1207');
    expect(bounds).not.toBeNull();
    expect(bounds!.minLat).toBeCloseTo(57.48, 6);
    expect(bounds!.maxLat).toBeCloseTo(57.49, 6);
    expect(bounds!.minLon).toBeCloseTo(12.07, 6);
    expect(bounds!.maxLon).toBeCloseTo(12.08, 6);
  });

  it('centres inside the cell, and the centre re-keys to the same cell', () => {
    const key = '5748_1207';
    const center = spawnCellCenter(key);
    expect(center).not.toBeNull();
    expect(cellKeyForCoords(center!.lat, center!.lng)).toBe(key);
  });

  it('is null for an invalid key', () => {
    expect(spawnCellCenter('nope')).toBeNull();
    expect(spawnCellBounds('nope')).toBeNull();
  });
});

describe('formatCellCenter', () => {
  it('formats to five decimals', () => {
    expect(formatCellCenter('5748_1207')).toBe('57.48500, 12.07500');
  });

  it('renders a dash for an invalid key', () => {
    expect(formatCellCenter('bad')).toBe('—');
  });
});

describe('spawnCellState', () => {
  it('maps approved to "approved" and not-approved to "revoked"', () => {
    // A crownSpawnCells document only exists because it was approved once, so a
    // non-approved doc is always a revoked area, never a fresh one.
    expect(spawnCellState({ approved: true })).toBe('approved');
    expect(spawnCellState({ approved: false })).toBe('revoked');
  });
});

describe('toSpawnCellSummary', () => {
  it('maps an approved document, converting Firestore timestamps to ISO', () => {
    const summary = toSpawnCellSummary('5748_1207', {
      cellKey: '5748_1207',
      approved: true,
      approvalNote: 'Parking lot, safe to stop.',
      approvedByUserId: 'admin-uid-123456',
      approvedAt: { toDate: () => new Date('2026-08-01T10:00:00.000Z') },
      revokedAt: null,
      revokedByUserId: null,
      revocationReason: null,
      updatedAt: { toDate: () => new Date('2026-08-01T10:00:00.000Z') },
    });
    expect(summary).toEqual({
      cellKey: '5748_1207',
      approved: true,
      approvalNote: 'Parking lot, safe to stop.',
      approvedByUserId: 'admin-uid-123456',
      approvedAt: '2026-08-01T10:00:00.000Z',
      revokedByUserId: null,
      revokedAt: null,
      revocationReason: null,
      updatedAt: '2026-08-01T10:00:00.000Z',
    });
  });

  it('treats a missing approved flag as not approved and falls back to the doc id', () => {
    const summary = toSpawnCellSummary('10_20', {});
    expect(summary.cellKey).toBe('10_20');
    expect(summary.approved).toBe(false);
    expect(summary.approvedAt).toBeNull();
    expect(spawnCellState(summary)).toBe('revoked');
  });

  it('maps a revoked document', () => {
    const summary = toSpawnCellSummary('10_20', {
      cellKey: '10_20',
      approved: false,
      revokedByUserId: 'admin-uid-999',
      revokedAt: { toDate: () => new Date('2026-08-01T12:00:00.000Z') },
      revocationReason: 'Roadworks — no longer safe.',
      updatedAt: { toDate: () => new Date('2026-08-01T12:00:00.000Z') },
    });
    expect(summary.approved).toBe(false);
    expect(summary.revokedByUserId).toBe('admin-uid-999');
    expect(summary.revocationReason).toBe('Roadworks — no longer safe.');
    expect(summary.revokedAt).toBe('2026-08-01T12:00:00.000Z');
  });
});
