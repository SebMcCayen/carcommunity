/**
 * partnerInsights.driveHeat — admin callable (contracts/functions/functions.json).
 *
 * Deployed via the `partnerInsights` export group as
 * `partnerInsights-driveHeat`.
 *
 * The admin read path for the aggregate DRIVE HEATMAP. The partner portal
 * separately projects only public cell geometry and relative bands. `partnerDriveHeat` is backend-only
 * (firestore.rules read,write:false — even for admins), so all reads require a
 * server-authorized callable. It is read-only: it simply returns the current aggregate document
 * the scheduled `partnerInsights-aggregateDriveHeat` job wrote.
 *
 * Defense in depth: every cell is re-checked against the ≥10 unique-contributor
 * floor at read time, so even a corrupt/legacy aggregate can never surface a
 * below-threshold cell. No user-level data is ever returned — a cell is only
 * `{ h3Index, contributorCount, weight }`.
 *
 * Admin-gated (requireAdminActor), App Check enforced.
 */

import { onCall } from '../access/callable';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import { DRIVE_HEAT_DOC_PATH } from './driveHeatAggregation';

export type { DriveHeatCellOut, DriveHeatResult } from './drive-heat-snapshot';
import { projectDriveHeatSnapshot, type DriveHeatResult } from './drive-heat-snapshot';

export const driveHeat = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
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
    return projectDriveHeatSnapshot(snap.data(), Date.now());
  },
);
