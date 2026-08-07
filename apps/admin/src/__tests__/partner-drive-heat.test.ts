/**
 * Unit tests for the partner drive-heat admin feature: the pure colour/legend
 * helpers and the callable wrapper. No DOM, no Mapbox, no h3-js runtime — the
 * map's geometry conversion is tested separately in drive-heat-map.test.ts.
 */

import { describe, expect, it, vi, beforeEach } from 'vitest';

const { callAdmin } = vi.hoisted(() => ({ callAdmin: vi.fn() }));
vi.mock('../lib/callables', () => ({ callAdmin }));

import {
  DRIVE_HEAT_COLORS,
  driveHeatBands,
  driveHeatColorStops,
  loadDriveHeat,
  maxWeight,
  type DriveHeatCell,
} from '@/features/partner-drive-heat';

function cells(weights: number[]): DriveHeatCell[] {
  return weights.map((weight, i) => ({
    h3Index: `8a1f2539b56${i}fff`,
    contributorCount: 10 + i,
    weight,
  }));
}

describe('maxWeight', () => {
  it('is 0 for an empty set', () => {
    expect(maxWeight([])).toBe(0);
  });
  it('returns the largest weight', () => {
    expect(maxWeight(cells([3, 40, 12]))).toBe(40);
  });
});

describe('driveHeatBands', () => {
  it('produces one full-width band when every cell shares a weight', () => {
    const bands = driveHeatBands(cells([10, 10, 10]));
    expect(bands).toHaveLength(1);
    expect(bands[0]!.max).toBeNull();
    expect(bands[0]!.color).toBe(DRIVE_HEAT_COLORS[DRIVE_HEAT_COLORS.length - 1]);
  });

  it('produces five ascending bands across the weight range', () => {
    const bands = driveHeatBands(cells([1, 25, 50, 75, 100]));
    expect(bands).toHaveLength(DRIVE_HEAT_COLORS.length);
    // Lower bounds strictly ascending; top band is open-ended.
    for (let i = 1; i < bands.length; i += 1) {
      expect(bands[i]!.min).toBeGreaterThan(bands[i - 1]!.min);
    }
    expect(bands[bands.length - 1]!.max).toBeNull();
    expect(bands.map((b) => b.color)).toEqual([...DRIVE_HEAT_COLORS]);
  });
});

describe('driveHeatColorStops', () => {
  it('is a flat [value, color, …] array with strictly ascending values', () => {
    const stops = driveHeatColorStops(cells([1, 25, 50, 75, 100]));
    expect(stops.length).toBe(DRIVE_HEAT_COLORS.length * 2);
    const values = stops.filter((_, i) => i % 2 === 0) as number[];
    const colors = stops.filter((_, i) => i % 2 === 1) as string[];
    for (let i = 1; i < values.length; i += 1) {
      expect(values[i]!).toBeGreaterThan(values[i - 1]!);
    }
    expect(colors).toEqual([...DRIVE_HEAT_COLORS]);
  });

  it('still yields a valid single stop when all weights are equal', () => {
    const stops = driveHeatColorStops(cells([7, 7]));
    // One band → one [value, color] pair; a valid (degenerate) interpolate input.
    expect(stops).toHaveLength(2);
    expect(typeof stops[0]).toBe('number');
    expect(stops[1]).toBe(DRIVE_HEAT_COLORS[DRIVE_HEAT_COLORS.length - 1]);
  });
});

describe('loadDriveHeat', () => {
  beforeEach(() => callAdmin.mockReset());

  it('calls the partnerInsights-driveHeat callable and returns its payload', async () => {
    const payload = { cells: cells([10]), resolution: 10, windowDays: 90, generatedAt: null };
    callAdmin.mockResolvedValue(payload);
    const result = await loadDriveHeat();
    expect(callAdmin).toHaveBeenCalledWith('partnerInsights-driveHeat', {});
    expect(result).toBe(payload);
  });
});
