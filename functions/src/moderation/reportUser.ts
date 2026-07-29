/**
 * moderation.reportUser — callable (contracts/functions/functions.json).
 *
 * Deployed via the `moderation` export group as `moderation-reportUser`.
 *
 * Reports a PERSON rather than a message: the escalation for behaviour that
 * doesn't reduce to one line of chat (a profile, a pattern, something that
 * happened off a reportable surface). Writes the same moderationReports
 * document as the two message-report callables so admins triage one queue.
 *
 * ACTOR GATE: requireActiveActor, not requireMemberActor — deliberately looser
 * than the two message callables and matching blocking.block/unblock, the
 * sibling personal-safety tool. Reporting someone is not a member feature; a
 * user whose subscription lapsed must still be able to escalate harassment.
 * Suspension and deletion still close the door (requireActiveActor).
 *
 * BLOCKING does not gate reporting, in either direction — same reasoning as the
 * message callables. Blocking someone and reporting them are the two halves of
 * the same response, and they are routinely done in that order.
 *
 * SELF-REPORT is rejected (invalid-argument). A user who does not exist, or
 * whose account is soft-deleted, is not-found — there is nothing left for a
 * moderator to action on a deleted account.
 *
 * WHAT IS CAPTURED: the reported uid, a snapshot of their PUBLIC profile
 * projection (displayName + avatarPath) so a later rename doesn't leave the
 * queue pointing at a name nobody recognises, the reason + note, and a tally.
 * NOT their message history, drives, garage, or anything else — see the long
 * note on buildUserReportDocument in moderation-core.ts. The "how many prior
 * reports" signal an admin actually needs is two integers on the per-target
 * moderationUserSummaries/{uid} aggregate, kept in the same transaction.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import {
  SELF_REPORT_MESSAGE,
  USER_NOT_FOUND_MESSAGE,
  parseReportUserInput,
} from './moderation-core';
import { fileUserReport, type ReportResponse } from './reportStore';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const reportUser = onCall(CALLABLE_OPTS, async (request): Promise<ReportResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseReportUserInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;

  if (input.reportedUserId === actor.uid) {
    throw new HttpsError('invalid-argument', SELF_REPORT_MESSAGE);
  }

  const snap = await db.collection('users').doc(input.reportedUserId).get();
  if (!snap.exists || toUserAccessState(snap.data()).deleted) {
    throw new HttpsError('not-found', USER_NOT_FOUND_MESSAGE);
  }
  const profile = snap.data() ?? {};

  return fileUserReport({
    reportedUserId: input.reportedUserId,
    reporterUserId: actor.uid,
    reason: input.reason,
    details: input.details,
    snapshot: {
      displayName: typeof profile.displayName === 'string' ? profile.displayName : null,
      avatarPath: typeof profile.avatarPath === 'string' ? profile.avatarPath : null,
    },
  });
});
