/**
 * events.removeChatMessage — admin callable
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-removeChatMessage`.
 * Requires an active admin via requireAdminActor (server-managed `admin`
 * custom claim + non-suspended, non-deleted users/{uid} state).
 *
 * Soft-removes a chat message (legacy moderation parity): the member-visible
 * body is blanked and moderationState flips to 'removed' — Security Rules
 * cannot redact fields per-read, so removed text must leave the
 * member-readable document. The original text is preserved in the
 * adminAuditEvents record, open reports on the message resolve, and nothing
 * is ever hard-deleted.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { buildChatMessageRemoval, parseRemoveChatMessageInput } from './chat-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

export interface RemoveChatMessageResponse {
  eventId: string;
  messageId: string;
  moderationState: 'removed';
}

export const removeChatMessage = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_ADMIN,
    cpu: CPU_ADMIN,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<RemoveChatMessageResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseRemoveChatMessageInput(request.data);
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
      if (message.moderationState === 'removed') {
        throw new HttpsError('failed-precondition', 'Message is already removed.');
      }

      // Resolve open reports for this message inside the same transaction.
      const openReports = await tx.get(
        eventRef
          .collection('messageReports')
          .where('messageId', '==', input.messageId)
          .where('status', 'in', ['new', 'under_review']),
      );

      tx.update(messageRef, buildChatMessageRemoval(actor.uid, serverTimestamp));
      for (const report of openReports.docs) {
        tx.update(report.ref, {
          status: 'resolved',
          reviewedAt: serverTimestamp(),
          reviewedByUserId: actor.uid,
        });
      }
      tx.set(
        db.collection('adminAuditEvents').doc(),
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'eventChat.removeMessage',
            targetType: 'eventChatMessage',
            targetId: input.messageId,
            reason: input.reason,
            details: {
              eventId: input.eventId,
              authorUserId: message.authorUserId,
              // Original text preserved for audit (member docs are blanked).
              originalMessage: message.message,
              resolvedReports: openReports.size,
            },
          },
          serverTimestamp,
        ),
      );
    });

    return { eventId: input.eventId, messageId: input.messageId, moderationState: 'removed' };
  },
);
