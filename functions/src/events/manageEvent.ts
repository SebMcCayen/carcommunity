/**
 * events.create / events.update — admin callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-create` and
 * `events-update`. Requires an active admin via requireAdminActor: the server-managed
 * `admin` custom claim plus a non-suspended, non-deleted Firestore
 * `users/{uid}` state with role admin or owner.
 *
 * Every event is stored as two documents (see events-core.ts): the
 * teaser-safe `events/{eventId}` and the member-gated
 * `events/{eventId}/details/private`. Both are written atomically in a
 * batch, plus an immutable adminAuditEvents record — legacy event-service
 * parity (audit actions `event.create` / `event.update` with changedFields).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  buildEventDocuments,
  buildEventUpdates,
  guardCoordinatePair,
  guardEventTimes,
  guardUpdatableStatus,
  parseCreateEventInput,
  parseUpdateEventInput,
  type EventStatus,
} from './events-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface EventIdResponse {
  eventId: string;
  status: EventStatus;
}

export const create = onCall(CALLABLE_OPTS, async (request): Promise<EventIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseCreateEventInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;

  const timesGuard = guardEventTimes(input.startsAt, input.endsAt);
  if (!timesGuard.ok) {
    throw new HttpsError(timesGuard.code, timesGuard.message);
  }
  const coordsGuard = guardCoordinatePair(input.latitude, input.longitude);
  if (!coordsGuard.ok) {
    throw new HttpsError(coordsGuard.code, coordsGuard.message);
  }

  const serverTimestamp = () => FieldValue.serverTimestamp();
  const { eventDoc, privateDoc } = buildEventDocuments(input, actor.uid, serverTimestamp);

  const eventRef = db.collection('events').doc();
  const batch = db.batch();
  batch.set(eventRef, eventDoc);
  batch.set(eventRef.collection('details').doc('private'), privateDoc);
  batch.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      {
        adminId: actor.uid,
        action: 'event.create',
        targetType: 'event',
        targetId: eventRef.id,
        reason: 'Event created.',
        details: { title: input.title, startsAt: input.startsAt },
      },
      serverTimestamp,
    ),
  );
  await batch.commit();

  return { eventId: eventRef.id, status: 'draft' };
});

export const update = onCall(CALLABLE_OPTS, async (request): Promise<EventIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseUpdateEventInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const eventRef = db.collection('events').doc(input.eventId);
  const privateRef = eventRef.collection('details').doc('private');

  const status = await db.runTransaction(async (tx) => {
    const eventSnap = await tx.get(eventRef);
    if (!eventSnap.exists) {
      throw new HttpsError('not-found', 'Event not found.');
    }
    const event = eventSnap.data() as {
      status: EventStatus;
      startsAt: FirebaseFirestore.Timestamp;
      endsAt: FirebaseFirestore.Timestamp | null;
    };

    const statusGuard = guardUpdatableStatus(event.status);
    if (!statusGuard.ok) {
      throw new HttpsError(statusGuard.code, statusGuard.message);
    }

    // Validate the effective times: incoming values override stored ones
    // (legacy parity — endsAt must stay after startsAt across partial edits).
    const effectiveStartsAt = input.startsAt ?? event.startsAt.toDate().toISOString();
    const effectiveEndsAt =
      input.endsAt !== undefined ? input.endsAt : (event.endsAt?.toDate().toISOString() ?? null);
    const timesGuard = guardEventTimes(effectiveStartsAt, effectiveEndsAt);
    if (!timesGuard.ok) {
      throw new HttpsError(timesGuard.code, timesGuard.message);
    }

    if (input.latitude !== undefined || input.longitude !== undefined) {
      const coordsGuard = guardCoordinatePair(input.latitude ?? null, input.longitude ?? null);
      if (!coordsGuard.ok) {
        throw new HttpsError(coordsGuard.code, coordsGuard.message);
      }
    }

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const { eventDoc, privateDoc, changedFields } = buildEventUpdates(input, serverTimestamp);
    if (changedFields.length === 0) {
      throw new HttpsError('invalid-argument', 'No event fields to update.');
    }

    if (Object.keys(eventDoc).length > 0) {
      tx.update(eventRef, eventDoc);
    }
    if (Object.keys(privateDoc).length > 0) {
      tx.set(privateRef, privateDoc, { merge: true });
    }
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'event.update',
          targetType: 'event',
          targetId: input.eventId,
          reason: 'Event updated.',
          details: { changedFields },
        },
        serverTimestamp,
      ),
    );

    return event.status;
  });

  return { eventId: input.eventId, status };
});
