/**
 * events.reportChatMessage — callable (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-reportChatMessage`.
 *
 * Files a report against a chat message
 * (events/{eventId}/messageReports/{reportId}). Reporter eligibility equals
 * chat read eligibility (legacy canReadEventChat). Reports deduplicate per
 * (message, reporter, reason) via a deterministic document ID — a repeat
 * report silently updates details, and the response never reveals whether a
 * previous report existed. Reports are never client-readable; the moderation
 * queue is backend/admin-only.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  CHAT_REPORT_DETAILS_MAX_LENGTH,
  buildChatReportDocument,
  chatReportDocId,
  parseReportChatMessageInput,
} from './chat-core';
import { requireChatParticipant } from './chatParticipant';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

export interface ReportChatMessageResponse {
  reported: true;
}

export const reportChatMessage = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<ReportChatMessageResponse> => {
    const parsed = parseReportChatMessageInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    const participant = await requireChatParticipant(request, input.eventId);

    const eventRef = db.collection('events').doc(input.eventId);
    const messageSnap = await eventRef.collection('messages').doc(input.messageId).get();
    if (!messageSnap.exists) {
      throw new HttpsError('not-found', 'Message not found.');
    }
    if (messageSnap.data()?.authorUserId === participant.uid) {
      throw new HttpsError('invalid-argument', 'You cannot report your own message.');
    }

    const reportRef = eventRef
      .collection('messageReports')
      .doc(chatReportDocId(input.messageId, participant.uid, input.reason));

    // Legacy upsert parity: a repeat report only refreshes details — it must
    // never reset a report the moderation team already moved to
    // under_review/resolved/dismissed, and never clears review metadata.
    await db.runTransaction(async (tx) => {
      const existing = await tx.get(reportRef);
      if (existing.exists) {
        const details = input.details?.trim()
          ? input.details.trim().slice(0, CHAT_REPORT_DETAILS_MAX_LENGTH)
          : null;
        tx.update(reportRef, { details });
        return;
      }
      tx.set(
        reportRef,
        buildChatReportDocument(
          {
            messageId: input.messageId,
            reporterUserId: participant.uid,
            reason: input.reason,
            details: input.details,
          },
          () => FieldValue.serverTimestamp(),
        ),
      );
    });

    return { reported: true };
  },
);
