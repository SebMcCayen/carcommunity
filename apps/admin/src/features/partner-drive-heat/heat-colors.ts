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

/** Evenly sample `n` items from `all` (n>=1), always including the last item. */
function sampleEven<T>(all: readonly T[], n: number): T[] {
  const last = all.length - 1;
  if (n <= 1) return [all[last]!];
  return Array.from({ length: n }, (_, i) => all[Math.round((i * last) / (n - 1))]!);
}

/**
 * Relative-density bands spanning the actual `[minWeight .. maxWeight]` range.
 *
 * The band COUNT is derived from the integer weight span, capped at the ramp
 * length: a narrow range (e.g. weights 1–4) yields fewer, non-overlapping bands
 * rather than five with duplicate bounds. Each band's lower bound is a distinct
 * integer, the TOP band is open-ended and anchored so the busiest cell always
 * reaches the darkest colour, and colours/labels are sampled evenly from the
 * ramp so the top is always the darkest / "Very high". Returns a SINGLE band
 * when every cell shares one weight (or there is one/zero cell). Bands describe
 * RELATIVE density — "how busy relative to the busiest area", never an absolute
 * count.
 */
export function driveHeatBands(cells: readonly DriveHeatCell[]): DriveHeatBand[] {
  const max = maxWeight(cells);
  const floor = cells.length > 0 ? minWeight(cells) : 1;
  if (max <= floor) {
    return [
      {
        min: floor,
        max: null,
        color: DRIVE_HEAT_COLORS[DRIVE_HEAT_COLORS.length - 1]!,
        labelKey: BAND_LABEL_KEYS[BAND_LABEL_KEYS.length - 1]!,
      },
    ];
  }
  // Integer weight levels available: at most one band per level, capped at the
  // ramp length. width = levels / n >= 1, so consecutive lower bounds (via
  // floor) are strictly increasing distinct integers.
  const levels = max - floor + 1;
  const n = Math.min(DRIVE_HEAT_COLORS.length, levels);
  const colors = sampleEven(DRIVE_HEAT_COLORS, n);
  const labels = sampleEven(BAND_LABEL_KEYS, n);
  return colors.map((color, i) => {
    const min = floor + Math.floor((i * levels) / n);
    const isTop = i === n - 1;
    return {
      min,
      max: isTop ? null : floor + Math.floor(((i + 1) * levels) / n),
      color,
      labelKey: labels[i]!,
    };
  });
}

/**
 * Mapbox GL `interpolate` colour stops for a `['get','weight']` fill, as a flat
 * `[value0, color0, value1, color1, …]` array. Anchored at the same band lower
 * bounds as {@link driveHeatBands} — which are already strictly ascending
 * distinct integers, so no bumping is needed and the busiest cell always lands
 * on the darkest colour.
 */
export function driveHeatColorStops(cells: readonly DriveHeatCell[]): Array<number | string> {
  const stops: Array<number | string> = [];
  for (const band of driveHeatBands(cells)) {
    stops.push(band.min, band.color);
  }
  return stops;
}
