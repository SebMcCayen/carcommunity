/**
 * badges.awardHelpfulMember — admin callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `badges` export group as `badges-awardHelpfulMember`.
 * Requires an active admin via requireAdminActor.
 *
 * Manually awards the helpful_member badge (the only manually awardable
 * key — legacy awardHelpfulMemberByAdmin). Requires a non-empty reason,
 * writes an adminAuditEvents record on first award only (idempotent repeats
 * never duplicate audit entries), and rejects suspended/deleted targets.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { isRestricted, toUserAccessState } from '../shared/access';
import { buildBadgeDocument, parseAwardHelpfulMemberInput } from './badge-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

export interface AwardHelpfulMemberResponse {
  targetUid: string;
  badgeKey: 'helpful_member';
  alreadyAwarded: boolean;
}

export const awardHelpfulMember = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<AwardHelpfulMemberResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseAwardHelpfulMemberInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { targetUid, reason } = parsed.input;

    const targetSnap = await db.collection('users').doc(targetUid).get();
    if (!targetSnap.exists) {
      throw new HttpsError('not-found', 'Target user not found.');
    }
    if (isRestricted(toUserAccessState(targetSnap.data()))) {
      throw new HttpsError(
        'failed-precondition',
        'Cannot award a badge to a suspended or deleted user.',
      );
    }

    const badgeRef = db
      .collection('users')
      .doc(targetUid)
      .collection('badges')
      .doc('helpful_member');
    const serverTimestamp = () => FieldValue.serverTimestamp();

    const alreadyAwarded = await db.runTransaction(async (tx) => {
      const existing = await tx.get(badgeRef);
      if (existing.exists) {
        // Idempotent: no duplicate award, no duplicate audit entry.
        return true;
      }
      tx.set(
        badgeRef,
        buildBadgeDocument(
          'helpful_member',
          { source: 'admin_manual' },
          serverTimestamp,
        ),
      );
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'badge.awardHelpfulMember',
            targetType: 'user',
            targetId: targetUid,
            reason,
            details: { badgeKey: 'helpful_member' },
          },
          serverTimestamp,
        ),
      );
      return false;
    });

    return { targetUid, badgeKey: 'helpful_member', alreadyAwarded };
  },
);
