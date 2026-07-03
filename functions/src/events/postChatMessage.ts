/**
 * events.postChatMessage — callable (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-postChatMessage`.
 *
 * Posts a plain-text message to a published event's chat
 * (events/{eventId}/messages). Per backend-domain-mapping.md, messages are
 * written only through this callable: it validates membership + RSVP
 * eligibility, trims and length-checks the text, denormalizes the author's
 * display name, and enforces the legacy rate limit (~5 messages per 30
 * seconds per user, across all events, via a collection-group query).
 *
 * Block-list read filtering belongs to the blocking domain (Phase 9,
 * domain list) — the legacy API filters blocked authors on read, not on
 * post, so this callable has no block checks.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  CHAT_RATE_LIMIT_MAX_MESSAGES,
  CHAT_RATE_LIMIT_WINDOW_MS,
  buildChatMessageDocument,
  parsePostChatMessageInput,
} from './chat-core';
import { requireChatParticipant } from './chatParticipant';

export interface PostChatMessageResponse {
  eventId: string;
  messageId: string;
}

export const postChatMessage = onCall(
  {
    region: 'europe-west1',
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

    const participant = await requireChatParticipant(request, input.eventId);

    // Legacy rate limit (~5 per 30s per user, all events). Collection-group
    // query on the composite index (authorUserId ASC, createdAt ASC).
    // Best-effort by design, matching the legacy fastify limiter (per-process
    // memory): concurrent invocations may briefly overshoot the cap. This is
    // an anti-spam knob, not a security invariant — no data integrity
    // depends on the exact count.
    const windowStart = Timestamp.fromMillis(Date.now() - CHAT_RATE_LIMIT_WINDOW_MS);
    const recent = await db
      .collectionGroup('messages')
      .where('authorUserId', '==', participant.uid)
      .where('createdAt', '>', windowStart)
      .count()
      .get();
    if (recent.data().count >= CHAT_RATE_LIMIT_MAX_MESSAGES) {
      throw new HttpsError(
        'resource-exhausted',
        'Too many messages — wait a moment before posting again.',
      );
    }

    const messageRef = db
      .collection('events')
      .doc(input.eventId)
      .collection('messages')
      .doc();
    await messageRef.set(
      buildChatMessageDocument(
        {
          authorUserId: participant.uid,
          authorDisplayName: participant.displayName,
          message: input.message,
        },
        () => FieldValue.serverTimestamp(),
      ),
    );

    return { eventId: input.eventId, messageId: messageRef.id };
  },
);
