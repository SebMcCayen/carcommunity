/**
 * police.onReportDeleted — reclaims a police pin's `votes/{uid}` verify ledger
 * when the pin document is deleted, by ANY path.
 *
 * WHY A TRIGGER (and not just police.remove's recursiveDelete). A pin's
 * `votes/{uid}` sub-collection is NOT reclaimed by the pin's own reclaim path:
 * the field-scoped Firestore TTL policy on `policeReports.expiresAt` deletes the
 * pin DOCUMENT but does NOT cascade into its sub-collections. `police.remove`
 * recursiveDeletes the votes itself, but that only covers the reporter's explicit
 * removal — the COMMON path is the ~40 min TTL expiry, which would otherwise leave
 * vote-ledger docs orphaned and accumulating storage indefinitely.
 *
 * A Firestore delete (including a TTL reclaim and an admin console delete) DOES
 * fire onDocumentDeleted, so this one trigger reclaims the votes whichever way the
 * pin went. It composes with police.remove's recursiveDelete without conflict:
 * whichever runs second finds the sub-collection already empty and is a no-op.
 *
 * BEST-EFFORT: the pin (and thus the map marker) is already gone by the time this
 * runs; a transient recursiveDelete failure is logged (pin id only, no PII) and
 * left for the next occasion rather than retried into a wedge. Deployed via the
 * `police` export group as `police-onReportDeleted` (europe-west1).
 */

import { onDocumentDeleted } from 'firebase-functions/v2/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { MAX_INSTANCES_TRIGGER, CPU_TRIGGER } from '../shared/instanceLimits';

export const onReportDeleted = onDocumentDeleted(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    cpu: CPU_TRIGGER,
    concurrency: 1,
    document: 'policeReports/{reportId}',
    memory: '256MiB',
    timeoutSeconds: 60,
  },
  async (event) => {
    // The deleted pin's own document ref (its sub-collections still exist until
    // swept). recursiveDelete on the doc ref removes the doc (already gone — a
    // no-op) and, crucially, its `votes/{uid}` sub-collection.
    const ref = db.collection('policeReports').doc(event.params.reportId);
    try {
      await db.recursiveDelete(ref);
    } catch (error) {
      logger.error('police.onReportDeleted: votes ledger cleanup failed; orphan left behind', {
        reportId: event.params.reportId,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  },
);
