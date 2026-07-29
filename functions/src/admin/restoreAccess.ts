/**
 * admin.restoreAccess — callable (contracts/functions/functions.json).
 *
 * Deployed via the `admin` export group as `admin-restoreAccess`.
 *
 * Restores a suspended user: clears the `suspended` custom claim, mirrors it
 * to the backend-managed `users/{uid}.suspended` field, and writes immutable
 * moderationActions + adminAuditEvents records via the Admin SDK. Admin- or
 * owner-only; admins cannot moderate owner accounts.
 *
 * The restored user regains access on their next ID-token refresh (≤ 1 hour)
 * or immediately after a forced token refresh / re-sign-in.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminAuth, db } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  applyPrivilegeChange,
  buildAdminAuditEvent,
  buildModerationAction,
  computeUpdatedClaims,
  guardModerationTarget,
  parseModerationInput,
} from './claims-core';
import { toUserAccessState } from '../shared/access';
import type { ModerationStatusResponse } from './suspendUser';
import { MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';

export const restoreAccess = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<ModerationStatusResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseModerationInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { targetUid, reason } = parsed.input;

    const targetUser = await adminAuth.getUser(targetUid).catch(() => null);
    if (!targetUser) {
      throw new HttpsError('not-found', 'Target user not found.');
    }

    const targetRef = db.collection('users').doc(targetUid);
    const targetSnap = await targetRef.get();
    const targetState = toUserAccessState(targetSnap.data());

    const guard = guardModerationTarget({
      actorUid: actor.uid,
      actorRole: actor.state.role,
      targetUid,
      targetRole: targetState.role,
    });
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }

    // Fail-safe ordering (see applyPrivilegeChange): restoring access
    // increases privileges, so the records + audit entry commit BEFORE the
    // claim is cleared — a partial failure never restores access without an
    // audit trail.
    const serverTimestamp = () => FieldValue.serverTimestamp();
    await applyPrivilegeChange({
      decreasesPrivilege: false,
      writeClaims: async () => {
        await adminAuth.setCustomUserClaims(
          targetUid,
          computeUpdatedClaims(targetUser.customClaims, { suspended: false }),
        );
      },
      commitRecords: async () => {
        const batch = db.batch();
        batch.set(
          targetRef,
          { suspended: false, updatedAt: FieldValue.serverTimestamp() },
          { merge: true },
        );
        batch.set(
          db.collection('moderationActions').doc(),
          buildModerationAction(
            {
              targetUserId: targetUid,
              actorUserId: actor.uid,
              actionType: 'restore_access',
              reason,
            },
            serverTimestamp,
          ),
        );
        batch.set(
          db.collection('adminAuditEvents').doc(),
          buildAdminAuditEvent(
            {
              adminId: actor.uid,
              action: 'user.restoreAccess',
              targetType: 'user',
              targetId: targetUid,
              reason,
            },
            serverTimestamp,
          ),
        );
        await batch.commit();
      },
    });

    return { targetUid, suspended: false };
  },
);
