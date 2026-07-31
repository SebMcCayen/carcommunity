/**
 * events.allowChatMessage — admin callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-allowChatMessage`.
 * Requires an active admin via requireAdminActor (server-managed `admin`
 * custom claim + non-suspended, non-deleted users/{uid} state).
 *
 * The moderator override that CLEARS an auto-hide: flips a message from
 * auto_hidden (or visible) to `allowed`, a TERMINAL state the
 * events-onMessageReportCreate trigger never re-hides — so an allowed message
 * cannot be auto-hidden again no matter how many further reports arrive. The
 * body is untouched (allow keeps the original text; it becomes visible to
 * everyone again), the message's open reports are dismissed, and the action is
 * written to adminAuditEvents.
 *
 * A `removed` message cannot be allowed — removal tombstones the body, so there
 * is nothing to reveal (failed-precondition).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { buildChatMessageAllow, parseAllowChatMessageInput } from './chat-core';
import { MAX_INSTANCES_ADMIN } from '../shared/instanceLimits';

export interface AllowChatMessageResponse {
  eventId: string;
  messageId: string;
  moderationState: 'allowed';
}

export const allowChatMessage = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<AllowChatMessageResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseAllowChatMessageInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    const eventRef = db.collection('events').doc(input.eventId);
    const messageRef = eventRef.collection('messages').doc(input.messageId);
    const serverTimestamp = () => FieldValue.serverTimestamp();

    await db.runTransaction(async (tx) => {
      const messageSnap = await tx.get(messageRef);
      if (!messageSnap.exists) {
        throw new HttpsError('not-found', 'Message not found.');
      }
      const message = messageSnap.data()!;
      const state = message.moderationState;
      if (state === 'removed') {
        throw new HttpsError(
          'failed-precondition',
          'A removed message cannot be allowed — its content is gone.',
        );
      }
      if (state === 'allowed') {
        throw new HttpsError('failed-precondition', 'Message is already allowed.');
      }

      // Dismiss open reports for this message — allowing it is the moderator's
      // judgement that the reports do not warrant removal.
      const openReports = await tx.get(
        eventRef
          .collection('messageReports')
          .where('messageId', '==', input.messageId)
          .where('status', 'in', ['new', 'under_review']),
      );

      tx.update(messageRef, buildChatMessageAllow(actor.uid, serverTimestamp));
      for (const report of openReports.docs) {
        tx.update(report.ref, {
          status: 'dismissed',
          reviewedAt: serverTimestamp(),
          reviewedByUserId: actor.uid,
        });
      }
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'eventChat.allowMessage',
            targetType: 'eventChatMessage',
            targetId: input.messageId,
            reason: 'Allowed after report review',
            details: {
              eventId: input.eventId,
              authorUserId: message.authorUserId,
              previousState: state ?? 'visible',
              dismissedReports: openReports.size,
            },
          },
          serverTimestamp,
        ),
      );
    });

    return { eventId: input.eventId, messageId: input.messageId, moderationState: 'allowed' };
  },
);
