/**
 * dm.reportMessage — callable (contracts/functions/functions.json).
 *
 * Deployed via the `dm` export group as `dm-reportMessage`.
 *
 * Reports a message in a 1:1 direct-message conversation
 * (conversations/{pairId}/messages/{messageId}). Writes the same
 * moderationReports document as chatchannels.reportMessage and
 * moderation.reportUser, so all three land in one admin queue.
 *
 * Eligibility mirrors the conversation's read rule: the caller must be one of
 * the two stored `members` (dm-core isConversationMember), checked with the
 * same requireMemberActor gate the rest of the dm domain uses. A missing
 * conversation and a conversation the caller isn't in both return NOT-FOUND,
 * never permission-denied — parity with dm.getMessages, so the report endpoint
 * can't be used to probe whether two people have a conversation.
 *
 * BLOCKING does not gate reporting. Blocking a DM partner is the normal first
 * move against someone abusive, and dm.sendMessage already refuses a blocked
 * pair — so if a block also disabled reporting, the messages you most want
 * moderated would be the ones you could never escalate.
 *
 * SNAPSHOTTING IS NOT OPTIONAL HERE. DM messages are readable only by the two
 * participants; the Firestore rules give admins no read path into
 * conversations at all, by design. Without the snapshot an admin would receive
 * a conversationId + messageId they are structurally unable to open, i.e. an
 * unactionable report. The callable copies exactly the one reported message —
 * never the thread — into the admin-read-only report. See moderation-core.ts.
 */

import { logger } from 'firebase-functions';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { isConversationMember } from './dm-core';
import {
  CONVERSATION_NOT_FOUND_MESSAGE,
  MALFORMED_MESSAGE_MESSAGE,
  MESSAGE_NOT_FOUND_MESSAGE,
  SELF_MESSAGE_REPORT_MESSAGE,
  parseReportDirectMessageInput,
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

  const parsed = parseReportDirectMessageInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;

  const convRef = db.collection('conversations').doc(input.conversationId);
  const convSnap = await convRef.get();
  if (!convSnap.exists || !isConversationMember(convSnap.data(), actor.uid)) {
    throw new HttpsError('not-found', CONVERSATION_NOT_FOUND_MESSAGE);
  }

  const messageSnap = await convRef.collection('messages').doc(input.messageId).get();
  if (!messageSnap.exists) {
    throw new HttpsError('not-found', MESSAGE_NOT_FOUND_MESSAGE);
  }
  const message = messageSnap.data() ?? {};
  const authorUserId = typeof message.senderUid === 'string' ? message.senderUid.trim() : '';
  if (authorUserId === '') {
    // Defensive: no write path can persist a DM message without a senderUid
    // (rules deny client writes; dm.sendMessage builds it from the actor uid).
    // See MALFORMED_MESSAGE_MESSAGE for why this refuses instead of filing a
    // report that names nobody.
    logger.error('dm.reportMessage: stored message has no author uid', {
      conversationId: input.conversationId,
      messageId: input.messageId,
    });
    throw new HttpsError('internal', MALFORMED_MESSAGE_MESSAGE);
  }
  if (authorUserId === actor.uid) {
    throw new HttpsError('invalid-argument', SELF_MESSAGE_REPORT_MESSAGE);
  }

  // DM message documents carry no denormalized author name (a 1:1 conversation
  // keeps memberProfiles on the parent instead — dm-core.ts), so the snapshot's
  // display name is read off the conversation's stored projection rather than
  // costing a users/{uid} lookup.
  const profiles = (convSnap.data()?.memberProfiles ?? {}) as Record<
    string,
    Record<string, unknown> | undefined
  >;
  const createdAt = message.createdAt;

  return fileMessageReport({
    surface: 'dm',
    scopeId: input.conversationId,
    messageId: input.messageId,
    reporterUserId: actor.uid,
    reason: input.reason,
    details: input.details,
    snapshot: toReportedMessageSnapshot({
      text: message.text,
      authorUserId,
      authorDisplayName: profiles[authorUserId]?.displayName,
      createdAtIso: createdAt instanceof Timestamp ? createdAt.toDate().toISOString() : null,
    }),
  });
});
