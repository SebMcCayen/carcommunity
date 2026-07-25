/**
 * Firestore access for the block-visibility mirror described in
 * ./block-visibility.ts.
 *
 * Two jobs, both deliberately tiny:
 *  - `loadHiddenUids(uid)` — the READ side used by every block-filtered
 *    callable. ONE document read per request, whatever the page size.
 *  - `applyPairVisibility(...)` — the WRITE side, driven exclusively by the
 *    `blocking-onBlockWrite` trigger. Never called from a callable, so the
 *    mirror can only ever be a consequence of an authoritative
 *    `userBlocks/{a}/blocked/{b}` write.
 */

import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  BLOCK_VISIBILITY_COLLECTION,
  HIDDEN_UIDS_FIELD,
  MAX_HIDDEN_UIDS,
  toHiddenUidSet,
} from './block-visibility';

function visibilityRef(uid: string) {
  return db.collection(BLOCK_VISIBILITY_COLLECTION).doc(uid);
}

/**
 * The set of uids mutually hidden from `uid`: everyone they blocked, plus
 * everyone who blocked them. ONE document read.
 *
 * A read failure is NOT swallowed — a silently empty set would render a blocked
 * party's messages, which is the exact outcome this feature exists to prevent.
 */
export async function loadHiddenUids(uid: string): Promise<Set<string>> {
  const snap = await visibilityRef(uid).get();
  return toHiddenUidSet(snap.data());
}

/**
 * Adds or removes ONE side of the symmetric mirror.
 *
 * REMOVAL is an unconditional `arrayRemove`: it is idempotent, needs no prior
 * read, and shrinking must never be gated on anything — an unblock has to be
 * able to land even from a document at the cap.
 *
 * ADDITION runs in a TRANSACTION so the MAX_HIDDEN_UIDS check is atomic. The
 * cheaper read-then-`arrayUnion` shape would let two blocks landing at the same
 * instant both observe `size == cap - 1` and both append, so the document could
 * exceed the cap the surrounding docs promise. Contention here is per-VIEWER and
 * per-block — a rate no user generates — so the retry cost is theoretical while
 * the guarantee is not. `arrayUnion` is still used for the write itself, so the
 * transaction only arbitrates the size check, never rewrites the whole array.
 *
 * Idempotent in both directions (arrayUnion of a present value and arrayRemove
 * of an absent one are both no-ops), so a trigger retry costs a write and
 * changes nothing.
 *
 * @returns true when the entry was applied; false when an ADD was skipped
 *   because the viewer is at the cap.
 */
export async function setHiddenUid(
  viewerUid: string,
  otherUid: string,
  hidden: boolean,
): Promise<boolean> {
  const ref = visibilityRef(viewerUid);
  if (!hidden) {
    await ref.set({ [HIDDEN_UIDS_FIELD]: FieldValue.arrayRemove(otherUid) }, { merge: true });
    return true;
  }

  return db.runTransaction(async (tx) => {
    const existing = toHiddenUidSet((await tx.get(ref)).data());
    if (existing.has(otherUid)) return true;
    if (existing.size >= MAX_HIDDEN_UIDS) return false;

    tx.set(ref, { [HIDDEN_UIDS_FIELD]: FieldValue.arrayUnion(otherUid) }, { merge: true });
    return true;
  });
}

/** Mirrors a pair's mutual-hidden state on BOTH sides. */
export async function applyPairVisibility(
  uidA: string,
  uidB: string,
  hidden: boolean,
): Promise<void> {
  await Promise.all([setHiddenUid(uidA, uidB, hidden), setHiddenUid(uidB, uidA, hidden)]);
}
