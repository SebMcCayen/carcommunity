/**
 * events-onRsvpWrite — Firestore trigger maintaining denormalized RSVP
 * counters on events/{eventId}.rsvpCounts (Phase 9b).
 *
 * Members write their own RSVP document directly
 * (events/{eventId}/rsvps/{uid}, Security-Rules-gated); this trigger keeps
 * the parent counters in sync so clients and the admin app never need to
 * scan the subcollection. FieldValue.increment writes are commutative, so
 * concurrent RSVP changes cannot race the counters, and a delta of zero
 * (e.g. a no-op rewrite of the same status) skips the write entirely.
 */

import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { computeRsvpCountDeltas, isZeroDeltas } from './events-core';

export const onRsvpWrite = onDocumentWritten(
  {
    region: 'europe-west1',
    document: 'events/{eventId}/rsvps/{uid}',
    memory: '256MiB',
    timeoutSeconds: 30,
  },
  async (firestoreEvent) => {
    const { eventId, uid } = firestoreEvent.params;
    const before = firestoreEvent.data?.before.data()?.status;
    const after = firestoreEvent.data?.after.data()?.status;

    const deltas = computeRsvpCountDeltas(before, after);
    if (isZeroDeltas(deltas)) {
      return;
    }

    const eventRef = db.collection('events').doc(eventId);
    try {
      await eventRef.update({
        'rsvpCounts.going': FieldValue.increment(deltas.going),
        'rsvpCounts.maybe': FieldValue.increment(deltas.maybe),
        'rsvpCounts.not_going': FieldValue.increment(deltas.not_going),
      });
    } catch (error) {
      // Swallow ONLY the parent-missing case (event deleted between the
      // RSVP write and this trigger) — retrying that can never succeed.
      // Everything else (transient Firestore failures etc.) rethrows so the
      // trigger retries instead of silently desynchronizing rsvpCounts.
      const code = (error as { code?: number | string }).code;
      if (code === 5 || code === 'not-found') {
        logger.warn('rsvpCounts update skipped: parent event missing', { eventId, uid });
        return;
      }
      throw error;
    }
  },
);
