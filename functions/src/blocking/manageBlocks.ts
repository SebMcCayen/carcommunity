/**
 * blocking.block / blocking.unblock — authenticated callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `blocking` export group as `blocking-block` and
 * `blocking-unblock`. Ports services/api/src/lib/blocking-service.ts:
 *
 *  - Backend is the sole source of truth for blocking decisions.
 *  - Blocks are directional and idempotent (re-blocking keeps the original
 *    createdAt; unblocking a non-existent block is a no-op, not an error).
 *  - Self-blocking is rejected. Blocking a missing/deleted user is not-found.
 *  - Privacy: a block never reveals to the target that it happened, and the
 *    blocked list (owner-only read of userBlocks/{uid}/blocked) never exposes
 *    who blocked the caller.
 *
 * Writes go through these callables only — userBlocks is owner-readable but
 * not client-writable (firestore.rules) so the denormalized displayName and
 * createdAt stay backend-controlled.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import {
  SELF_BLOCK_MESSAGE,
  buildBlockDocument,
  parseBlockInput,
  parseUnblockInput,
  toBlockedUserSummary,
  type BlockedUserSummary,
} from './blocking-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface BlockUserResult {
  block: BlockedUserSummary;
  /** Hint to clients to refresh visibility-filtered data (e.g. markers). */
  shouldRefreshMarkers: true;
}

export interface UnblockUserResult {
  unblocked: boolean;
}

function blockedRef(blockerUid: string, blockedUid: string) {
  return db.collection('userBlocks').doc(blockerUid).collection('blocked').doc(blockedUid);
}

export const block = onCall(CALLABLE_OPTS, async (request): Promise<BlockUserResult> => {
  const actor = await requireActiveActor(request);

  const parsed = parseBlockInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const targetUserId = parsed.input.targetUserId;

  if (targetUserId === actor.uid) {
    throw new HttpsError('invalid-argument', SELF_BLOCK_MESSAGE);
  }

  const targetSnap = await db.collection('users').doc(targetUserId).get();
  if (!targetSnap.exists || toUserAccessState(targetSnap.data()).deleted) {
    // not-found (never permission-denied) so callers cannot probe deletion.
    throw new HttpsError('not-found', 'User not found.');
  }
  const displayName = (targetSnap.data()?.displayName as string | null | undefined) ?? null;

  const ref = blockedRef(actor.uid, targetUserId);
  // Single timestamp so a new block's persisted createdAt and the returned
  // blockedAt are identical (no client/server clock drift between them).
  const now = new Date();
  const summary = await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) {
      // Idempotent: keep the original createdAt; refresh the denormalized name.
      const data = existing.data() ?? {};
      const createdAt =
        (data.createdAt as Timestamp | undefined)?.toDate().toISOString() ?? now.toISOString();
      tx.set(ref, { displayName }, { merge: true });
      return toBlockedUserSummary(targetUserId, displayName, createdAt);
    }
    tx.set(ref, buildBlockDocument(targetUserId, displayName, () => Timestamp.fromDate(now)));
    return toBlockedUserSummary(targetUserId, displayName, now.toISOString());
  });

  return { block: summary, shouldRefreshMarkers: true };
});

export const unblock = onCall(CALLABLE_OPTS, async (request): Promise<UnblockUserResult> => {
  const actor = await requireActiveActor(request);

  const parsed = parseUnblockInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const targetUserId = parsed.input.targetUserId;

  if (targetUserId === actor.uid) {
    return { unblocked: false };
  }

  const ref = blockedRef(actor.uid, targetUserId);
  const unblocked = await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (!existing.exists) {
      return false;
    }
    tx.delete(ref);
    return true;
  });

  return { unblocked };
});
