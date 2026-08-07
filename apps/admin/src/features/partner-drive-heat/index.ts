/**
 * Partner DRIVE HEATMAP admin feature module.
 *
 * The read side of the anonymised drive heatmap shown to partners so they can
 * pick where to buy in-app digital billboards. It calls the admin-gated
 * `partnerInsights-driveHeat` callable (the only read path — the aggregate
 * collection is backend-only) and re-exports the PURE colour/legend helpers
 * from ./heat-colors (kept firebase-free so the map + tests can use them without
 * loading the callable client).
 *
 * PRIVACY: every value handled here is already anonymised on the backend — a
 * cell is only `{ h3Index, contributorCount, weight }`, floored at ≥10 unique
 * contributors. There is no user-level data to leak.
 */

import type { DriveHeatCell, DriveHeatResult } from '@carcommunity/shared/partner-insights';

import { callAdmin } from '../../lib/callables';

export type { DriveHeatCell, DriveHeatResult };
export type { DriveHeatBand } from './heat-colors';
export {
  DRIVE_HEAT_COLORS,
  driveHeatBands,
  driveHeatColorStops,
  maxWeight,
  minWeight,
} from './heat-colors';

/** Loads the anonymised drive-heat aggregate via the admin callable. */
export async function loadDriveHeat(): Promise<DriveHeatResult> {
  return callAdmin<DriveHeatResult>('partnerInsights-driveHeat', {});
}
