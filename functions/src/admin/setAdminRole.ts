/**
 * admin.setAdminRole — callable (contracts/functions/functions.json).
 *
 * Deployed via the `admin` export group as `admin-setAdminRole`.
 *
 * Grants or revokes the `admin: true` custom claim and mirrors the change to
 * the backend-managed `users/{uid}.role` field ('admin' | 'user'). Admin- or
 * owner-only; there is no self-elevation path (callers can never change
 * their own role) and the owner role is never granted, revoked, or
 * overwritten here. Every call writes an immutable adminAuditEvents record
 * via the Admin SDK.
 *
 * Custom-claim refresh semantics (docs/migration/backend-domain-mapping.md):
 * claims propagate to the target on their next ID-token refresh (≤ 1 hour).
 * On revocation the target's refresh tokens are revoked so a stale admin
 * token cannot be silently renewed.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminAuth, db } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  applyPrivilegeChange,
  buildAdminAuditEvent,
  computeUpdatedClaims,
  guardSetAdminRole,
  parseSetAdminRoleInput,
} from './claims-core';
import { toUserAccessState } from '../shared/access';

export interface SetAdminRoleResponse {
  targetUid: string;
  role: 'admin' | 'user';
  admin: boolean;
}

export const setAdminRole = onCall(
  {
    region: 'europe-west1',
    memory: '256MiB',
    timeoutSeconds: 30,
    // App Check is enforced in production (contracts/functions/functions.json
    // appCheck: true); the emulator suite has no App Check provider.
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<SetAdminRoleResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseSetAdminRoleInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { targetUid, admin: grantAdmin, reason } = parsed.input;

    // The target must exist in Firebase Authentication — claims attach to
    // the Auth user, not the Firestore document.
    const targetUser = await adminAuth.getUser(targetUid).catch(() => null);
    if (!targetUser) {
      throw new HttpsError('not-found', 'Target user not found.');
    }

    const targetRef = db.collection('users').doc(targetUid);
    const targetSnap = await targetRef.get();
    const targetState = toUserAccessState(targetSnap.data());

    const guard = guardSetAdminRole({
      actorUid: actor.uid,
      targetUid,
      targetRole: targetState.role,
    });
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }

    const nextRole = grantAdmin ? 'admin' : 'user';

    // Fail-safe ordering (see applyPrivilegeChange): on revocation the
    // enforcement claim is removed (and refresh tokens revoked) BEFORE the
    // records commit, so a partial failure can never leave claim-based admin
    // access behind; on grant the records commit first, so a partial failure
    // never grants access without an audit trail.
    await applyPrivilegeChange({
      decreasesPrivilege: !grantAdmin,
      writeClaims: async () => {
        await adminAuth.setCustomUserClaims(
          targetUid,
          computeUpdatedClaims(targetUser.customClaims, { admin: grantAdmin }),
        );
        // Revoking admin is security-sensitive: invalidate refresh tokens so
        // the stale admin claim cannot outlive the current ID token (≤ 1 h).
        if (!grantAdmin) {
          await adminAuth.revokeRefreshTokens(targetUid);
        }
      },
      commitRecords: async () => {
        const batch = db.batch();
        batch.set(
          targetRef,
          { role: nextRole, updatedAt: FieldValue.serverTimestamp() },
          { merge: true },
        );
        batch.set(
          db.collection('adminAuditEvents').doc(),
          buildAdminAuditEvent(
            {
              adminId: actor.uid,
              action: 'user.setAdminRole',
              targetType: 'user',
              targetId: targetUid,
              reason,
              details: { admin: grantAdmin, role: nextRole },
            },
            () => FieldValue.serverTimestamp(),
          ),
        );
        await batch.commit();
      },
    });

    return { targetUid, role: nextRole, admin: grantAdmin };
  },
);
