/**
 * events-onMessageReportCreate — Firestore onCreate trigger on
 * events/{eventId}/messageReports/{reportId}.
 *
 * Auto-moderation: when SEVERAL DISTINCT USERS have reported the same event-chat
 * message, the message is hidden for everyone (moderationState → auto_hidden)
 * so the community stops seeing it while a moderator reviews. Clients render a
 * collapsed "Show reported message" placeholder for auto_hidden messages; an
 * admin can then Remove it (permanent tombstone) or Allow it (un-hide, and
 * never auto-hide again) from the KCC admin Event-chat page.
 *
 * WHY A TRIGGER (not folded into events.reportChatMessage): the trigger is
 * idempotent and cannot race the report callable — every report write, whoever
 * made it, re-derives the distinct-reporter count and re-checks the message
 * state transactionally, so concurrent reports and a concurrent admin
 * allow/remove converge safely. firebase-functions v2 defaults `retry` to
 * false, which is fine here: a dropped invocation is self-healed by the next
 * report (and a moderator still sees the report queue regardless).
 *
 * DISTINCT reporters, not report documents: report ids embed the reason
 * (`${messageId}_${reporterUserId}_${reason}`), so one user filing under
 * several reasons mints several docs but is still ONE reporter. The threshold
 * counts distinct reporterUserId (chat-core countDistinctReporters).
 *
 * NO NEW INDEX: the count query is a single equality filter on `messageId`
 * within one event's messageReports subcollection, served by the automatic
 * single-field index.
 */

import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import {
  buildChatMessageAutoHide,
  countDistinctReporters,
  shouldAutoHide,
} from './chat-core';
import { MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';

export const onMessageReportCreate = onDocumentCreated(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    document: 'events/{eventId}/messageReports/{reportId}',
    memory: '256MiB',
    timeoutSeconds: 30,
  },
  async (firestoreEvent) => {
    const { eventId } = firestoreEvent.params;
    const messageId = firestoreEvent.data?.data()?.messageId;
    if (typeof messageId !== 'string' || messageId.length === 0) {
      // A malformed report (no messageId) can auto-hide nothing.
      return;
    }

    const eventRef = db.collection('events').doc(eventId);
    const messageRef = eventRef.collection('messages').doc(messageId);

    // Cheap pre-check (ONE document read) before the distinct-reporter
    // aggregation: only a still-`visible` message can be auto-hidden. Once a
    // message is terminal (auto_hidden/allowed/removed) or gone, every further
    // report would otherwise pay for an ever-growing "all reports for this
    // message" scan just to no-op — wasteful and report-spam-abusable. This is
    // ONLY an optimization; correctness comes from the transactional re-check
    // below (the state can still change between here and the transaction).
    const preSnap = await messageRef.get();
    if (!preSnap.exists || preSnap.data()?.moderationState !== 'visible') {
      return;
    }

    // Distinct-reporter count for this message. Single-equality query → auto
    // single-field index, no composite index required.
    const reportsSnap = await eventRef
      .collection('messageReports')
      .where('messageId', '==', messageId)
      .get();
    const distinctReporters = countDistinctReporters(
      reportsSnap.docs.map((doc) => ({ reporterUserId: doc.data().reporterUserId })),
    );
    if (!shouldAutoHide(distinctReporters)) {
      return;
    }

    try {
      await db.runTransaction(async (tx) => {
        const snap = await tx.get(messageRef);
        if (!snap.exists) {
          return;
        }
        // Only visible → auto_hidden. allowed / removed (and an already
        // auto_hidden message) are terminal for the trigger — an admin Allow is
        // sticky, a Remove is permanent, and re-hiding an already-hidden message
        // is a redundant write.
        if (snap.data()?.moderationState !== 'visible') {
          return;
        }
        tx.update(messageRef, buildChatMessageAutoHide(distinctReporters, () => FieldValue.serverTimestamp()));
      });
    } catch (error) {
      const code = (error as { code?: number | string }).code;
      // Parent message deleted between the report and this trigger — retrying
      // can never succeed. Everything else rethrows (no retry configured, but
      // the next report self-heals).
      if (code === 5 || code === 'not-found') {
        logger.warn('auto-hide skipped: message missing', { eventId, messageId });
        return;
      }
      throw error;
    }
  },
);
