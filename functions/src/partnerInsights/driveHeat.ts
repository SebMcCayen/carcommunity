/**
 * partnerInsights.driveHeat — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `partnerInsights` export group as
 * `partnerInsights-driveHeat`.
 *
 * The read path for the anonymised DRIVE HEATMAP: returns the aggregated H3 heat
 * cells the admin/partner map renders. `partnerDriveHeat` is backend-only
 * (firestore.rules read,write:false — even for admins), so this callable is the
 * only way in. It is read-only: it simply returns the current aggregate document
 * the scheduled `partnerInsights-aggregateDriveHeat` job wrote.
 *
 * Defense in depth: every cell is re-checked against the ≥10 unique-contributor
 * floor at read time, so even a corrupt/legacy aggregate can never surface a
 * below-threshold cell. No user-level data is ever returned — a cell is only
 * `{ h3Index, contributorCount, weight }`.
 *
 * Admin-gated (requireAdminActor), App Check enforced.
 */

import { onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';
import { MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD } from './insights-core';
import {
  DRIVE_HEAT_DOC_PATH,
  DRIVE_HEAT_MAX_CELLS,
  DRIVE_HEAT_WINDOW_DAYS,
} from './driveHeatAggregation';
import { DRIVE_HEAT_H3_RESOLUTION } from './drive-heat-core';

/** One anonymised heat cell returned to the admin map. */
export interface DriveHeatCellOut {
  h3Index: string;
  contributorCount: number;
  weight: number;
}

/** Raw callable payload (no envelope — callAdmin returns this directly). */
export interface DriveHeatResult {
  cells: DriveHeatCellOut[];
  /** H3 resolution the cells are at, so the client renders boundaries to match. */
  resolution: number;
  windowDays: number;
  /** ISO 8601 build time of the aggregate, or null if it has never run. */
  generatedAt: string | null;
}

function toIsoOrNull(value: unknown): string | null {
  if (value && typeof (value as { toDate?: unknown }).toDate === 'function') {
    return (value as { toDate: () => Date }).toDate().toISOString();
  }
  return null;
}

/** A valid H3 index is 15–16 lowercase hex chars; reject anything else. */
function isH3Index(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{15,16}$/.test(value);
}

export const driveHeat = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<DriveHeatResult> => {
    await requireAdminActor(request);

    const snap = await db
      .collection(DRIVE_HEAT_DOC_PATH.collection)
      .doc(DRIVE_HEAT_DOC_PATH.doc)
      .get();
    const data = snap.exists ? snap.data() : undefined;

    const rawCells = Array.isArray(data?.cells) ? (data!.cells as unknown[]) : [];
    const cells: DriveHeatCellOut[] = [];
    for (const raw of rawCells) {
      if (!raw || typeof raw !== 'object') continue;
      const cell = raw as Record<string, unknown>;
      const contributorCount = Number(cell.contributorCount);
      const weight = Number(cell.weight);
      // Re-apply the privacy floor at read time (defense in depth) and validate
      // the stored shape, failing closed on anything malformed.
      if (!isH3Index(cell.h3Index)) continue;
      if (!Number.isFinite(contributorCount) || contributorCount < MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD) {
        continue;
      }
      if (!Number.isFinite(weight) || weight < 0) continue;
      cells.push({ h3Index: cell.h3Index, contributorCount, weight });
      if (cells.length >= DRIVE_HEAT_MAX_CELLS) break;
    }

    return {
      cells,
      resolution:
        typeof data?.resolution === 'number' ? (data.resolution as number) : DRIVE_HEAT_H3_RESOLUTION,
      windowDays:
        typeof data?.windowDays === 'number' ? (data.windowDays as number) : DRIVE_HEAT_WINDOW_DAYS,
      generatedAt: toIsoOrNull(data?.generatedAt),
    };
  },
);
