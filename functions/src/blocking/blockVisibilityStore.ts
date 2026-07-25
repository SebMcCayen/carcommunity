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
 * Uses arrayUnion/arrayRemove so concurrent block writes for different pairs
 * never clobber each other (no read-modify-write race). The MAX_HIDDEN_UIDS cap
 * is therefore enforced on the ADD path with a preceding read: if the viewer is
 * already at the cap the entry is skipped rather than growing the document the
 * client holds a listener on. Removal is always applied — shrinking is safe and
 * must never be blocked by the cap.
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

  const existing = toHiddenUidSet((await ref.get()).data());
  if (existing.has(otherUid)) return true;
  if (existing.size >= MAX_HIDDEN_UIDS) return false;

  await ref.set({ [HIDDEN_UIDS_FIELD]: FieldValue.arrayUnion(otherUid) }, { merge: true });
  return true;
}

/** Mirrors a pair's mutual-hidden state on BOTH sides. */
export async function applyPairVisibility(
  uidA: string,
  uidB: string,
  hidden: boolean,
): Promise<void> {
  await Promise.all([setHiddenUid(uidA, uidB, hidden), setHiddenUid(uidB, uidA, hidden)]);
}
