/**
 * PURE colour + legend maths for the drive heatmap. No Firebase, no callAdmin,
 * no DOM, no Mapbox — kept separate from the feature's index.ts (which imports
 * the callable client) so the map component and its unit tests can pull these
 * helpers without dragging in the Firebase config. Unit-tested in
 * __tests__/partner-drive-heat.test.ts.
 */

import type { DriveHeatCell } from '@carcommunity/shared/partner-insights';

/**
 * Sequential 5-step ramp (ColorBrewer "Reds"), light → dark. A single-hue
 * sequential ramp reads correctly as "more = darker/redder" for viewers with
 * colour-vision deficiency and sits legibly over the light Mapbox basemap.
 */
export const DRIVE_HEAT_COLORS = ['#fee5d9', '#fcae91', '#fb6a4a', '#de2d26', '#a50f15'] as const;

/** Relative-density band, from least to most driven. Labels are i18n keys. */
export interface DriveHeatBand {
  /** Inclusive lower weight bound for this band. */
  min: number;
  /** Exclusive upper weight bound, or null for the top (open-ended) band. */
  max: number | null;
  color: string;
  /** i18n key describing the band (e.g. drive density "Low" … "Very high"). */
  labelKey: string;
}

const BAND_LABEL_KEYS = [
  'driveHeat.bandLow',
  'driveHeat.bandModerate',
  'driveHeat.bandBusy',
  'driveHeat.bandHigh',
  'driveHeat.bandVeryHigh',
] as const;

/** The largest cell weight in the set (0 when there are no cells). */
export function maxWeight(cells: readonly DriveHeatCell[]): number {
  let max = 0;
  for (const c of cells) {
    if (c.weight > max) max = c.weight;
  }
  return max;
}

/** The smallest cell weight in the set (0 when there are no cells). */
export function minWeight(cells: readonly DriveHeatCell[]): number {
  if (cells.length === 0) return 0;
  let min = Infinity;
  for (const c of cells) {
    if (c.weight < min) min = c.weight;
  }
  return min;
}

/**
 * Five relative-density bands spanning the actual `[minWeight .. maxWeight]`
 * range, split into equal weight ranges. Returns a SINGLE full-width band when
 * every cell shares one weight (or there is one/zero cell) so the legend never
 * shows five identical ranges. Bands describe RELATIVE density — colour is "how
 * busy relative to the busiest area", never an absolute count.
 */
export function driveHeatBands(cells: readonly DriveHeatCell[]): DriveHeatBand[] {
  const max = maxWeight(cells);
  const floor = cells.length > 0 ? minWeight(cells) : 1;
  const topColor = DRIVE_HEAT_COLORS[DRIVE_HEAT_COLORS.length - 1]!;
  const topLabel = BAND_LABEL_KEYS[BAND_LABEL_KEYS.length - 1]!;
  if (max <= floor) {
    return [{ min: floor, max: null, color: topColor, labelKey: topLabel }];
  }
  const span = max - floor;
  const step = span / DRIVE_HEAT_COLORS.length;
  return DRIVE_HEAT_COLORS.map((color, i) => {
    const isTop = i === DRIVE_HEAT_COLORS.length - 1;
    return {
      min: Math.round(floor + step * i),
      max: isTop ? null : Math.round(floor + step * (i + 1)),
      color,
      labelKey: BAND_LABEL_KEYS[i]!,
    };
  });
}

/**
 * Mapbox GL `interpolate` colour stops for a `['get','weight']` fill, as a flat
 * `[value0, color0, value1, color1, …]` array. Anchored at the same band lower
 * bounds as {@link driveHeatBands} so the map and the legend agree exactly, with
 * strictly ascending inputs (Mapbox requires it).
 */
export function driveHeatColorStops(cells: readonly DriveHeatCell[]): Array<number | string> {
  const bands = driveHeatBands(cells);
  const stops: Array<number | string> = [];
  let prev = -1;
  for (const band of bands) {
    const value = band.min <= prev ? prev + 1 : band.min;
    stops.push(value, band.color);
    prev = value;
  }
  return stops;
}
