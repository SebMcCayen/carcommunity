/**
 * badges.grantEarlyTester — admin callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `badges` export group as `badges-grantEarlyTester`.
 * Requires an active admin via requireAdminActor.
 *
 * Manually grants the exclusive early_tester ("Grundare" / Founder) badge to a
 * hand-picked list of UIDs — the app's earliest test users. There are NO
 * earning criteria and nothing in the backend awards this badge automatically:
 * an admin invokes this callable with the specific UID list on demand.
 *
 * Mirrors awardHelpfulMember's write pattern (buildBadgeDocument + a
 * create-if-absent transaction + one adminAuditEvents record on FIRST award
 * only), but over a batch of UIDs. Idempotent per UID: granting an already-held
 * badge is a no-op that writes no duplicate document and no duplicate audit
 * entry. Suspended/deleted or non-existent targets are SKIPPED (not fatal) so a
 * single bad UID never aborts the rest of the batch — the per-UID status is
 * returned instead.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { isRestricted, toUserAccessState } from '../shared/access';
import { buildBadgeDocument, parseGrantEarlyTesterInput } from './badge-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

/** Outcome for one UID in the batch. */
export type GrantEarlyTesterStatus = 'granted' | 'alreadyGranted' | 'skipped';

export interface GrantEarlyTesterResult {
  uid: string;
  status: GrantEarlyTesterStatus;
}

export interface GrantEarlyTesterResponse {
  badgeKey: 'early_tester';
  results: GrantEarlyTesterResult[];
  grantedCount: number;
  alreadyGrantedCount: number;
  skippedCount: number;
}

export const grantEarlyTester = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 60,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<GrantEarlyTesterResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseGrantEarlyTesterInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { uids, reason } = parsed.input;

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const results: GrantEarlyTesterResult[] = [];

    for (const targetUid of uids) {
      const targetSnap = await db.collection('users').doc(targetUid).get();
      if (!targetSnap.exists || isRestricted(toUserAccessState(targetSnap.data()))) {
        // Missing, suspended or deleted target — skip, do not abort the batch.
        results.push({ uid: targetUid, status: 'skipped' });
        continue;
      }

      const badgeRef = db
        .collection('users')
        .doc(targetUid)
        .collection('badges')
        .doc('early_tester');

      const alreadyGranted = await db.runTransaction(async (tx) => {
        const existing = await tx.get(badgeRef);
        if (existing.exists) {
          // Idempotent: no duplicate award, no duplicate audit entry.
          return true;
        }
        tx.set(
          badgeRef,
          buildBadgeDocument('early_tester', { source: 'admin_manual' }, serverTimestamp),
        );
        tx.set(
          db.collection('adminAuditEvents').doc(),
          buildAdminAuditEvent(
            {
              adminId: actor.uid,
              action: 'badge.grantEarlyTester',
              targetType: 'user',
              targetId: targetUid,
              reason,
              details: { badgeKey: 'early_tester' },
            },
            serverTimestamp,
          ),
        );
        return false;
      });

      results.push({
        uid: targetUid,
        status: alreadyGranted ? 'alreadyGranted' : 'granted',
      });
    }

    return {
      badgeKey: 'early_tester',
      results,
      grantedCount: results.filter((r) => r.status === 'granted').length,
      alreadyGrantedCount: results.filter((r) => r.status === 'alreadyGranted').length,
      skippedCount: results.filter((r) => r.status === 'skipped').length,
    };
  },
);
