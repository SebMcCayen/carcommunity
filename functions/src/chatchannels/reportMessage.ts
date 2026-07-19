/**
 * chatchannels.reportMessage — callable (contracts/functions/functions.json).
 *
 * Deployed via the `chatchannels` export group as `chatchannels-reportMessage`.
 *
 * Reports a message in one of the two CHANNEL chats: the global community
 * channel (communityChat/global/messages) or a convoy channel
 * (convoyChats/{convoyId}/messages). The third product chat, friends-DMs, has
 * its own callable (dm.reportMessage) because its eligibility rule is
 * different; both write the same moderationReports document so admins triage
 * one queue.
 *
 * Eligibility mirrors each channel's READ rule exactly — you can report what
 * you can see, and nothing else:
 *  - community: any active member (requireMemberActor, matching communityChat.*)
 *  - convoy: an ACCEPTED member of that convoy (requireAcceptedConvoyMember,
 *    the same gate convoyChat.post/list use — a missing convoy or an outsider
 *    is not-found so a convoy can't be probed via the report endpoint).
 *
 * BLOCKING does not gate reporting, deliberately and in either direction. You
 * block someone because they are behaving badly; making you unblock them to
 * report them would be exactly backwards, and letting a harasser pre-emptively
 * block their target to disarm the report button would be worse. (The community
 * channel applies no server-side block filter to reads either — chat-core.ts —
 * so a blocked user's message is still on screen and must still be reportable.)
 *
 * The reported message is SNAPSHOTTED into the report: channel messages carry a
 * retention TTL (120 / 30 days) and are hard-deleted when it fires, so a report
 * holding only a messageId would go blank. See moderation-core.ts.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { COMMUNITY_CHANNEL_ID } from './chat-core';
import { requireAcceptedConvoyMember } from './convoyMembership';
import {
  MESSAGE_NOT_FOUND_MESSAGE,
  SELF_MESSAGE_REPORT_MESSAGE,
  parseReportChannelMessageInput,
  toReportedMessageSnapshot,
} from '../moderation/moderation-core';
import { fileMessageReport, type ReportResponse } from '../moderation/reportStore';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const reportMessage = onCall(CALLABLE_OPTS, async (request): Promise<ReportResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseReportChannelMessageInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;

  // Scope + eligibility. `convoyId` is guaranteed present for the convoy channel
  // and absent otherwise by the parser's refinement.
  let scopeId: string;
  let messagesRef: FirebaseFirestore.CollectionReference;
  if (input.channel === 'convoy') {
    const convoyId = input.convoyId!;
    await requireAcceptedConvoyMember(convoyId, actor.uid);
    scopeId = convoyId;
    messagesRef = db.collection('convoyChats').doc(convoyId).collection('messages');
  } else {
    scopeId = COMMUNITY_CHANNEL_ID;
    messagesRef = db.collection('communityChat').doc(COMMUNITY_CHANNEL_ID).collection('messages');
  }

  const messageSnap = await messagesRef.doc(input.messageId).get();
  if (!messageSnap.exists) {
    throw new HttpsError('not-found', MESSAGE_NOT_FOUND_MESSAGE);
  }
  const message = messageSnap.data() ?? {};
  const authorUserId = typeof message.senderUid === 'string' ? message.senderUid : '';
  if (authorUserId === actor.uid) {
    throw new HttpsError('invalid-argument', SELF_MESSAGE_REPORT_MESSAGE);
  }

  const createdAt = message.createdAt;
  return fileMessageReport({
    surface: input.channel,
    scopeId,
    messageId: input.messageId,
    reporterUserId: actor.uid,
    reason: input.reason,
    details: input.details,
    snapshot: toReportedMessageSnapshot({
      text: message.text,
      authorUserId,
      authorDisplayName: message.senderDisplayName,
      createdAtIso: createdAt instanceof Timestamp ? createdAt.toDate().toISOString() : null,
    }),
  });
});
