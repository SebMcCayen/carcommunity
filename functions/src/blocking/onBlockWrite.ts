/**
 * blocking-onBlockWrite — fans an authoritative block edge out to the
 * denormalized stores that make a block MUTUALLY INVISIBLE.
 *
 * The authoritative block list lives at
 * userBlocks/{blockerUid}/blocked/{blockedUid} in Firestore (written only by
 * the blocking.block / blocking.unblock callables). It is directional and
 * owner-read-only, which is exactly what the read surfaces cannot use directly:
 * the blocked side may not enumerate who blocked them, and no security rule can
 * reach across to the other party's subcollection cheaply. So this trigger
 * maintains three mirrors, each in the shape its consumer can actually enforce:
 *
 * 1. RTDB `liveLocationBlocks/{blockerUid}/{blockedUid} = true` — RTDB security
 *    rules cannot read Firestore, so live-location marker reads
 *    (liveLocation/{uid}/latest) could not honour blocks. The
 *    database.rules.json read rule denies a marker read in BOTH directions off
 *    this node, so the LIVE MAP hides a blocked pair from each other at the
 *    rules layer. (Unchanged by the block-invisibility work — it already did
 *    this; the discovery callable live.listNearby filters both directions too.)
 *
 * 2. Firestore `blockVisibility/{uid}.hiddenUids` — the symmetric union used by
 *    the CHAT read paths (see blocking/block-visibility.ts). One document read
 *    per callable, one snapshot listener per client session, instead of a
 *    per-message block lookup.
 *
 * 3. The pair's DM conversation document — its `lastMessage` preview is the one
 *    piece of counterparty content a list query still delivers to a blocked
 *    party's device, so it is redacted while the block stands and restored when
 *    it is lifted (see dm/blockedConversation.ts).
 *
 * SYMMETRY AND EDGE COUNTING: mirrors 2 and 3 describe a PAIR, not a direction.
 * They stay applied while EITHER direction is blocked, so an unblock only clears
 * them once the opposite edge is gone too — otherwise A unblocking B would
 * restore B's visibility even though B still blocks A.
 *
 * Every step is idempotent (a set to a fixed value, arrayUnion/arrayRemove, or a
 * marker-guarded transaction), so an error rethrown here retries the whole
 * fan-out safely rather than leaving a mirror out of sync.
 */

import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { logger } from 'firebase-functions';
import { adminRtdb, db } from '../firebase';
import { shouldHidePair } from './block-visibility';
import { applyPairVisibility } from './blockVisibilityStore';
import { setConversationBlocked } from '../dm/blockedConversation';
import { MAX_INSTANCES_TRIGGER, CPU_TRIGGER } from '../shared/instanceLimits';

export const blockMirrorRef = (blockerUid: string, blockedUid: string) =>
  adminRtdb.ref(`liveLocationBlocks/${blockerUid}/${blockedUid}`);

/** True when `blockerUid` currently blocks `blockedUid` in the authoritative store. */
async function blockEdgeExists(blockerUid: string, blockedUid: string): Promise<boolean> {
  const snap = await db
    .collection('userBlocks')
    .doc(blockerUid)
    .collection('blocked')
    .doc(blockedUid)
    .get();
  return snap.exists;
}

export const onBlockWrite = onDocumentWritten(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    cpu: CPU_TRIGGER,
    concurrency: 1,
    document: 'userBlocks/{blockerUid}/blocked/{blockedUid}',
    memory: '256MiB',
    timeoutSeconds: 60,
  },
  async (firestoreEvent) => {
    const { blockerUid, blockedUid } = firestoreEvent.params;
    const existsAfter = firestoreEvent.data?.after.exists ?? false;

    try {
      // 1. Directed RTDB mirror — a directed block edge is present → mirror it;
      //    absent → remove it. The read rule checks both directions itself.
      await blockMirrorRef(blockerUid, blockedUid).set(existsAfter ? true : null);

      // 2 + 3. Pair-scoped mirrors. The reverse edge is only worth reading when
      // this one just went away: while it exists the pair is hidden regardless.
      const reverseExists = existsAfter ? false : await blockEdgeExists(blockedUid, blockerUid);
      const hidden = shouldHidePair(existsAfter, reverseExists);

      await applyPairVisibility(blockerUid, blockedUid, hidden);
      await setConversationBlocked(blockerUid, blockedUid, hidden);
    } catch (error) {
      // Rethrow so the trigger retries rather than silently leaving a mirror out
      // of sync with the authoritative Firestore block.
      logger.error('block fan-out failed', {
        blockerUid,
        blockedUid,
        existsAfter,
        error: String(error),
      });
      throw error;
    }
  },
);
