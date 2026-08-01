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
 * Audit: an immutable adminAuditEvents record (action `user.delete`) is written
 * AFTER the purge with the actor uid, target uid, reason, server timestamp and
 * a snapshot of the target's displayName/role in `details`. It is keyed by the
 * ACTOR + target uid — never a reference into the deleted user's own (now gone)
 * data — and lives in the retained adminAuditEvents collection the purge never
 * touches.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminAuth, db } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  buildAdminAuditEvent,
  guardDeleteUserTarget,
  guardNotLastAdmin,
  parseModerationInput,
} from './claims-core';
import { toUserAccessState } from '../shared/access';
import { purgeUserData } from '../account/scheduled';
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

  // Existence: either the Auth user or the Firestore profile must be present.
  // A user in a partial state (one side already gone) is still purgeable.
  const targetUser = await adminAuth.getUser(targetUid).catch(() => null);
  const targetSnap = await db.collection('users').doc(targetUid).get();
  if (!targetUser && !targetSnap.exists) {
    throw new HttpsError('not-found', 'Target user not found.');
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
  // the end; disabling an already-absent user is skipped.
  if (targetUser) {
    await adminAuth.updateUser(targetUid, { disabled: true });
    await adminAuth.revokeRefreshTokens(targetUid);
  }

  // The comprehensive, idempotent erasure — the SAME routine self-service
  // deletion and the inactivity sweep run (functions/src/account/scheduled.ts).
  await purgeUserData(targetUid);

  // Immutable audit record. adminAuditEvents is retained (never purged), and
  // this write is keyed by the actor + target uid, not a reference into the
  // now-deleted user's data.
  await db
    .collection('adminAuditEvents')
    .doc()
    .set(
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

  return { targetUid, deleted: true };
});
