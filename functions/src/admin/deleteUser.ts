/**
 * admin.deleteUser — callable (contracts/functions/functions.json).
 *
 * Deployed via the `admin` export group as `admin-deleteUser`.
 *
 * IRREVERSIBLE, PRIVACY-SENSITIVE. Immediately and comprehensively erases a
 * target user and ALL their data — the admin-initiated counterpart to the
 * member's own account deletion. It reuses the SINGLE canonical erasure
 * routine `purgeUserData` (functions/src/account/scheduled.ts) that backs
 * self-service deletion and the inactivity sweep, so every collection,
 * subcollection, social-graph mirror, Realtime Database subtree, Cloud
 * Storage prefix and the Firebase Auth user are removed exactly as documented
 * in functions/src/account/deletion-core.ts — nothing is re-implemented or
 * partially copied here, which is what previously left orphaned records.
 *
 * Unlike account.deleteAccount (soft delete + 30-day retention purge), this is
 * an IMMEDIATE hard purge: the admin explicitly confirms the destruction in
 * the KCC admin UI (type-to-confirm), so there is no accidental-deletion
 * recovery window to protect.
 *
 * Safety guards (admin- or owner-only via requireAdminActor):
 *  - An admin can NEVER delete their own account here (guardDeleteUserTarget) —
 *    self-deletion risks admin lockout; they must use account deletion.
 *  - Admins cannot delete owner accounts; only owners can (guardDeleteUserTarget).
 *  - The LAST remaining admin/owner can never be deleted (guardNotLastAdmin),
 *    counted from the authoritative `users` collection.
 *
 * Fail-safe ordering: the Auth user is DISABLED and its refresh tokens revoked
 * BEFORE the purge runs, so a partial failure leaves the account locked out
 * rather than usable (purgeUserData deletes the now-disabled Auth user at the
 * end; it is idempotent and safe to retry).
 *
 * Audit: an adminAuditEvents record (action `user.delete`) is written AFTER the
 * purge with the actor uid, target uid, reason, server timestamp and a snapshot
 * of the target's displayName/role in `details`. The document id is deterministic
 * and keyed on the TARGET uid alone (`user-delete_${targetUid}`) — one record per
 * deleted user — and the write is an idempotent CREATE, so a retried invocation
 * leaves the first record (and its createdAt) untouched: that is what makes it
 * immutable on retry. The actor and target uids are stored as FIELDS
 * (adminId / targetId), never encoded into the id and never a reference into the
 * deleted user's own (now gone) data. It lives in the retained adminAuditEvents
 * collection the purge never touches.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminAuth, db } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  buildAdminAuditEvent,
  guardDeleteUserTarget,
  guardNotLastAdmin,
  isAuthUserNotFoundError,
  parseModerationInput,
} from './claims-core';
import { toUserAccessState } from '../shared/access';
import { purgeUserData } from '../account/scheduled';
import { isAlreadyExistsError } from '../chatchannels/chat-core';
import { MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  // The full erasure sweep (doc trees, mirrors, RTDB, storage, Auth user) is
  // heavier than the other admin callables, so it gets the scheduled-purge
  // memory/timeout budget rather than the 256MiB/30s moderation default.
  memory: '512MiB' as const,
  timeoutSeconds: 300,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface DeleteUserResponse {
  targetUid: string;
  deleted: true;
}

/**
 * Counts NON-deleted admin/owner accounts OTHER than `targetUid`. Used only
 * when the target is itself privileged, to enforce guardNotLastAdmin. Admin
 * accounts are few, so a bounded `role in [...]` read is cheap and needs no
 * composite index (single-field `role` is auto-indexed).
 */
async function countOtherActiveAdmins(targetUid: string): Promise<number> {
  const snap = await db.collection('users').where('role', 'in', ['admin', 'owner']).get();
  let count = 0;
  for (const docSnap of snap.docs) {
    if (docSnap.id === targetUid) continue;
    if (docSnap.data()?.deleted === true) continue;
    count += 1;
  }
  return count;
}

export const deleteUser = onCall(CALLABLE_OPTS, async (request): Promise<DeleteUserResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseModerationInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { targetUid, reason } = parsed.input;

  // Resolve the Auth record, failing CLOSED: only the "no such user" error is
  // the benign "no Auth record" case; any OTHER Auth error (transient outage,
  // quota, misconfiguration) is re-thrown so a destructive delete never proceeds
  // — and never skips the fail-safe lockdown — on an unverifiable Auth state.
  let targetUser: Awaited<ReturnType<typeof adminAuth.getUser>> | null = null;
  try {
    targetUser = await adminAuth.getUser(targetUid);
  } catch (error) {
    if (!isAuthUserNotFoundError(error)) throw error;
  }

  const targetSnap = await db.collection('users').doc(targetUid).get();

  // No Auth record AND no profile → the target does not exist at all.
  if (!targetUser && !targetSnap.exists) {
    throw new HttpsError('not-found', 'Target user not found.');
  }
  // The authoritative users/{uid} doc is MISSING (e.g. a partially-provisioned
  // account). Fail CLOSED rather than defaulting the role to 'user': the
  // owner-protection and last-admin guards cannot be verified without it, and a
  // destructive delete must never run on an unverifiable role/status.
  if (!targetSnap.exists) {
    throw new HttpsError(
      'failed-precondition',
      "Cannot verify the target user's role: the users/{uid} document is missing. Refusing to delete.",
    );
  }
  const targetState = toUserAccessState(targetSnap.data());

  // Self / owner-protection guard.
  const guard = guardDeleteUserTarget({
    actorUid: actor.uid,
    targetUid,
    actorRole: actor.state.role,
    targetRole: targetState.role,
  });
  if (!guard.ok) {
    throw new HttpsError(guard.code, guard.message);
  }

  // Last-admin guard (only when the target is itself privileged).
  if (targetState.role === 'admin' || targetState.role === 'owner') {
    const otherActiveAdminCount = await countOtherActiveAdmins(targetUid);
    const lastAdminGuard = guardNotLastAdmin({
      targetRole: targetState.role,
      otherActiveAdminCount,
    });
    if (!lastAdminGuard.ok) {
      throw new HttpsError(lastAdminGuard.code, lastAdminGuard.message);
    }
  }

  // Snapshot for the audit record BEFORE the data is destroyed.
  const displayName =
    typeof targetSnap.data()?.displayName === 'string'
      ? (targetSnap.data()!.displayName as string)
      : null;

  // Fail-safe lockdown: lock the account out FIRST so a partial purge cannot
  // leave a usable account. purgeUserData deletes the (disabled) Auth user at
  // the end; an already-absent Auth user (getUser returned not-found) is skipped.
  if (targetUser) {
    try {
      await adminAuth.updateUser(targetUid, { disabled: true });
      await adminAuth.revokeRefreshTokens(targetUid);
    } catch (error) {
      // The Auth user vanished between getUser() above and here — a retry after
      // a partial prior purge, or a concurrent deletion race. That is the benign
      // case: there is nothing left to lock out, so skip the lockdown and PROCEED
      // to purge the remaining Firestore data (idempotent intent preserved). Any
      // OTHER Auth error (transient outage, quota) is real — stay fail-closed and
      // abort before touching the data.
      if (!isAuthUserNotFoundError(error)) throw error;
    }
  }

  // The comprehensive, idempotent erasure — the SAME routine self-service
  // deletion and the inactivity sweep run (functions/src/account/scheduled.ts).
  await purgeUserData(targetUid);

  // Audit record, retained in adminAuditEvents (never purged). The DOCUMENT ID is
  // deterministic and keyed on the TARGET uid (`user-delete_${targetUid}`) — one
  // record per deleted user. The write is an idempotent CREATE so the FIRST
  // record wins: a retried or duplicate invocation fails with ALREADY_EXISTS and
  // is ignored, leaving the original record — and its createdAt — untouched
  // (genuinely immutable on retry; a plain set() would re-stamp createdAt). The
  // actor and target uids are stored as FIELDS (adminId / targetId), not encoded
  // into the id, and never a reference into the now-deleted user's own data.
  try {
    await db
      .collection('adminAuditEvents')
      .doc(`user-delete_${targetUid}`)
      .create(
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'user.delete',
            targetType: 'user',
            targetId: targetUid,
            reason,
            details: { role: targetState.role, displayName },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );
  } catch (error) {
    // A prior invocation for this target already wrote the immutable record. The
    // deletion itself has still succeeded, so skip the duplicate audit write;
    // any other error is unexpected and propagates.
    if (!isAlreadyExistsError(error)) throw error;
  }

  return { targetUid, deleted: true };
});
