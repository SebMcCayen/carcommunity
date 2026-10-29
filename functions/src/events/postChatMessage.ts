/**
 * events.postChatMessage — callable (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-postChatMessage`.
 *
 * Posts a plain-text message to a published event's chat
 * (events/{eventId}/messages). Per backend-domain-mapping.md, messages are
 * written only through this callable: it validates membership + RSVP
 * eligibility, trims and length-checks the text, denormalizes the author's
 * display name, and atomically enforces the legacy rate limit (5 messages per 30
 * seconds per user, across all events, via a collection-group query).
 *
 * Block-list read filtering belongs to the blocking domain (Phase 9,
 * domain list) — the legacy API filters blocked authors on read, not on
 * post, so this callable has no block checks.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireChatSender } from '../shared/chat-actor';
import { stageChatSendGuard, throwChatRateLimited } from '../shared/chat-send-guard';
import {
  CHAT_RATE_LIMIT_MAX_MESSAGES,
  CHAT_RATE_LIMIT_WINDOW_MS,
  buildChatMessageDocument,
  parsePostChatMessageInput,
} from './chat-core';
import { requireChatParticipant } from './chatParticipant';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

export interface PostChatMessageResponse {
  eventId: string;
  messageId: string;
}

export const postChatMessage = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<PostChatMessageResponse> => {
    const parsed = parsePostChatMessageInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    if (!input.message.trim()) {
      throw new HttpsError('invalid-argument', 'Message cannot be empty.');
    }

    // Optional keys preserve legacy callers and first-write-wins retry behavior.
    const messageRef =
      input.clientId === undefined
        ? db.collection('events').doc(input.eventId).collection('messages').doc()
        : db.collection('events').doc(input.eventId).collection('messages').doc(input.clientId);
    const outcome = await db.runTransaction(async (tx) => {
      const participant = await requireChatParticipant(request, input.eventId, tx);
      await requireChatSender(tx, participant.uid);
      const existing = await tx.get(messageRef);
      if (existing.exists) {
        if (existing.get('authorUserId') !== participant.uid)
          throw new HttpsError('already-exists', 'Message id already used.');
        return { kind: 'sent' as const };
      }
      const now = Date.now();
      // Bounded legacy rolling-window query; reading/writing the SAME UID guard
      // serializes concurrent sends even across different events. No count/write gap.
      const recent = await tx.get(
        db
          .collectionGroup('messages')
          .where('authorUserId', '==', participant.uid)
          .where('createdAt', '>', Timestamp.fromMillis(now - CHAT_RATE_LIMIT_WINDOW_MS))
          .orderBy('createdAt', 'desc')
          .limit(CHAT_RATE_LIMIT_MAX_MESSAGES),
      );
      if (recent.size >= CHAT_RATE_LIMIT_MAX_MESSAGES)
        throw new HttpsError(
          'resource-exhausted',
          'Too many messages — wait a moment before posting again.',
        );
      const guard = await stageChatSendGuard(db, tx, participant.uid, now);
      if (!guard.allowed) return { kind: 'denied' as const, guard };
      tx.create(
        messageRef,
        buildChatMessageDocument(
          {
            authorUserId: participant.uid,
            authorDisplayName: participant.displayName,
            message: input.message,
          },
          () => FieldValue.serverTimestamp(),
        ),
      );
      return { kind: 'sent' as const };
    });
    if (outcome.kind === 'denied') throwChatRateLimited(outcome.guard);
    return { eventId: input.eventId, messageId: messageRef.id };
  },
);
