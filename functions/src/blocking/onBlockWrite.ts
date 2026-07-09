/**
 * blocking-onBlockWrite — mirrors the Firestore block graph into Realtime
 * Database so RTDB security rules can enforce it.
 *
 * The authoritative block list lives at
 * userBlocks/{blockerUid}/blocked/{blockedUid} in Firestore (written only by
 * the blocking.block / blocking.unblock callables). RTDB security rules
 * cannot read Firestore, so live-location marker reads
 * (liveLocation/{uid}/latest) could not honour blocks — a blocked user could
 * still read the blocker's live position while they shared.
 *
 * This trigger keeps a minimal boolean mirror at
 * liveLocationBlocks/{blockerUid}/{blockedUid} = true (removed on unblock).
 * The database.rules.json read rule denies a marker read in BOTH directions
 * (either party having blocked the other), so blocking now hides live
 * location symmetrically, matching the app's block semantics elsewhere.
 *
 * Idempotent: a create/update writes `true`, a delete removes the node.
 * The mirror carries no PII — only the existence of a directed block edge,
 * which is exactly what the rule checks.
 */

import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { logger } from 'firebase-functions';
import { adminRtdb } from '../firebase';

export const blockMirrorRef = (blockerUid: string, blockedUid: string) =>
  adminRtdb.ref(`liveLocationBlocks/${blockerUid}/${blockedUid}`);

export const onBlockWrite = onDocumentWritten(
  {
    region: 'europe-west1',
    document: 'userBlocks/{blockerUid}/blocked/{blockedUid}',
    memory: '256MiB',
    timeoutSeconds: 30,
  },
  async (firestoreEvent) => {
    const { blockerUid, blockedUid } = firestoreEvent.params;
    const existsAfter = firestoreEvent.data?.after.exists ?? false;

    const ref = blockMirrorRef(blockerUid, blockedUid);
    try {
      // A directed block edge is present → mirror it; absent → remove it.
      await ref.set(existsAfter ? true : null);
    } catch (error) {
      // Rethrow so the trigger retries rather than silently leaving the RTDB
      // mirror out of sync with the authoritative Firestore block.
      logger.error('liveLocationBlocks mirror update failed', {
        blockerUid,
        blockedUid,
        existsAfter,
        error: String(error),
      });
      throw error;
    }
  },
);
