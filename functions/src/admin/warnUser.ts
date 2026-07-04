/**
 * admin.warnUser — admin callable (contracts/functions/functions.json),
 * Phase 9o.
 *
 * Issues a formal warning: an immutable moderationActions record (type
 * `warning`) and a `user.warn` adminAuditEvents record commit atomically,
 * and the target receives an essential `account_warning` in-app
 * notification through the Phase 9l writer (essential categories are
 * delivered even to suspended users and cannot be opted out of).
 *
 * Deliberate deviation from legacy, documented in the moderation
 * contract: the legacy status enum's `warned` state is not ported — the
 * target access model keeps only the backend-managed `suspended`/
 * `deleted` booleans, and a warning never restricts access. The warning
 * history lives in moderationActions; the user learns of it via the
 * notification.
 *
 * Same actor guards as admin.suspendUser: backend-verified admin, and
 * owner accounts can only be moderated by owners.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminAuth, db } from '../firebase';
import { requireAdminActor } from './actorContext';
import {
  buildAdminAuditEvent,
  buildModerationAction,
  guardModerationTarget,
  parseModerationInput,
} from './claims-core';
import { toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface WarnUserResponse {
  targetUid: string;
  actionId: string;
}

export const warnUser = onCall(CALLABLE_OPTS, async (request): Promise<WarnUserResponse> => {
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
  const targetSnap = await db.collection('users').doc(targetUid).get();
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

  const serverTimestamp = () => FieldValue.serverTimestamp();
  const actionRef = db.collection('moderationActions').doc();
  const batch = db.batch();
  batch.set(
    actionRef,
    buildModerationAction(
      { targetUserId: targetUid, actorUserId: actor.uid, actionType: 'warning', reason },
      serverTimestamp,
    ),
  );
  batch.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      { adminId: actor.uid, action: 'user.warn', targetType: 'user', targetId: targetUid, reason },
      serverTimestamp,
    ),
  );
  await batch.commit();

  // Essential account notice (9l): delivered even to suspended users,
  // cannot be opted out of. Deterministic ID (the moderation action's) so
  // a retried callable never duplicates the notice. Best-effort — the
  // moderation record is already committed and is the source of truth.
  try {
    await writeInAppNotification(
      targetUid,
      {
        category: 'account_warning',
        title: 'Varning utfärdad',
        previewText: 'Ditt konto har fått en varning från moderatorerna.',
        body: reason,
      },
      `warn-${actionRef.id}`,
    );
  } catch (error) {
    logger.warn('Warning notification delivery failed', {
      targetUid,
      actionId: actionRef.id,
      error: String(error),
    });
  }

  return { targetUid, actionId: actionRef.id };
});
