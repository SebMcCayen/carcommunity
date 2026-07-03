/**
 * admin.suspendUser — callable (contracts/functions/functions.json).
 *
 * Deployed via the `admin` export group as `admin-suspendUser`.
 *
 * Suspends a user: sets the `suspended: true` custom claim, mirrors it to
 * the backend-managed `users/{uid}.suspended` field, writes an immutable
 * moderationActions record, and writes an immutable adminAuditEvents record
 * — all via the Admin SDK. Admin- or owner-only; admins cannot moderate
 * owner accounts, and nobody can suspend themselves.
 *
 * Suspension always overrides feature access (Security Rules deny writes and
 * member-gated reads when `auth.token.suspended == true`), but suspended
 * users retain access to support and account-deletion paths. The target's
 * refresh tokens are revoked so the suspension claim takes effect at the
 * next ID-token refresh (≤ 1 hour) rather than at next sign-in.
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

export interface ModerationStatusResponse {
  targetUid: string;
  suspended: boolean;
}

export const suspendUser = onCall(
  {
    region: 'europe-west1',
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

    // Fail-safe ordering (see applyPrivilegeChange): suspension reduces
    // privileges, so the enforcement claim is set (and refresh tokens
    // revoked) BEFORE the records commit — a partial failure locks the user
    // down rather than leaving them unsuspended in the rules' eyes.
    const serverTimestamp = () => FieldValue.serverTimestamp();
    await applyPrivilegeChange({
      decreasesPrivilege: true,
      writeClaims: async () => {
        await adminAuth.setCustomUserClaims(
          targetUid,
          computeUpdatedClaims(targetUser.customClaims, { suspended: true }),
        );
        // Revoke refresh tokens so the claim cannot be dodged by silent
        // token renewal.
        await adminAuth.revokeRefreshTokens(targetUid);
      },
      commitRecords: async () => {
        const batch = db.batch();
        batch.set(
          targetRef,
          { suspended: true, updatedAt: FieldValue.serverTimestamp() },
          { merge: true },
        );
        batch.set(
          db.collection('moderationActions').doc(),
          buildModerationAction(
            {
              targetUserId: targetUid,
              actorUserId: actor.uid,
              actionType: 'permanent_suspension',
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
              action: 'user.suspend',
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

    return { targetUid, suspended: true };
  },
);
