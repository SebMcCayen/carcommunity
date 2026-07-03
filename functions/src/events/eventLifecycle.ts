/**
 * events.publish / events.cancel / events.complete — admin callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-publish`,
 * `events-cancel`, and `events-complete`. Requires an active admin via requireAdminActor: the server-managed
 * `admin` custom claim plus a non-suspended, non-deleted Firestore
 * `users/{uid}` state with role admin or owner.
 *
 * Status transitions mirror the legacy event-service:
 * - publish: draft only; requires title + approximateArea; start must not be
 *   in the past.
 * - cancel: draft or published; requires a reason; sets cancelledAt; never
 *   hard-deletes.
 * - complete: published only. Legacy also evaluated event badges
 *   (first_event / five_events) for 'going' attendees — badge evaluation
 *   migrates with the badges domain (Phase 9, domain 6) and hooks in here.
 *
 * Each transition writes an immutable adminAuditEvents record in the same
 * transaction that changes the status.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  guardCancellable,
  guardCompletable,
  guardPublishable,
  parseCancelEventInput,
  parseEventIdInput,
  type EventStatus,
  type GuardResult,
} from './events-core';
import type { EventIdResponse } from './manageEvent';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

interface StoredEvent {
  status: EventStatus;
  title: string;
  approximateArea: string;
  startsAt: Timestamp;
}

/** Shared transition runner: guard → status update → audit record, atomically. */
async function transitionEvent(params: {
  eventId: string;
  actorUid: string;
  action: 'event.publish' | 'event.cancel' | 'event.complete';
  reason: string;
  guard: (event: StoredEvent) => GuardResult;
  statusUpdate: (serverTimestamp: () => unknown) => Record<string, unknown>;
  auditDetails?: (event: StoredEvent) => Record<string, unknown>;
}): Promise<EventStatus> {
  const eventRef = db.collection('events').doc(params.eventId);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(eventRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Event not found.');
    }
    const event = snap.data() as StoredEvent;

    const guard = params.guard(event);
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }

    const serverTimestamp = () => FieldValue.serverTimestamp();
    const update = params.statusUpdate(serverTimestamp);
    tx.update(eventRef, update);
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: params.actorUid,
          action: params.action,
          targetType: 'event',
          targetId: params.eventId,
          reason: params.reason,
          ...(params.auditDetails ? { details: params.auditDetails(event) } : {}),
        },
        serverTimestamp,
      ),
    );

    return update.status as EventStatus;
  });
}

export const publish = onCall(CALLABLE_OPTS, async (request): Promise<EventIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseEventIdInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { eventId } = parsed.input;

  const status = await transitionEvent({
    eventId,
    actorUid: actor.uid,
    action: 'event.publish',
    reason: 'Event published.',
    guard: (event) =>
      guardPublishable(
        {
          status: event.status,
          title: event.title,
          approximateArea: event.approximateArea,
          startsAt: event.startsAt.toDate(),
        },
        new Date(),
      ),
    statusUpdate: (serverTimestamp) => ({ status: 'published', updatedAt: serverTimestamp() }),
    auditDetails: (event) => ({
      title: event.title,
      startsAt: event.startsAt.toDate().toISOString(),
    }),
  });

  return { eventId, status };
});

export const cancel = onCall(CALLABLE_OPTS, async (request): Promise<EventIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseCancelEventInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { eventId, reason } = parsed.input;

  const status = await transitionEvent({
    eventId,
    actorUid: actor.uid,
    action: 'event.cancel',
    reason,
    guard: (event) => guardCancellable(event.status),
    statusUpdate: (serverTimestamp) => ({
      status: 'cancelled',
      cancelledAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    }),
  });

  return { eventId, status };
});

export const complete = onCall(CALLABLE_OPTS, async (request): Promise<EventIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseEventIdInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { eventId } = parsed.input;

  const status = await transitionEvent({
    eventId,
    actorUid: actor.uid,
    action: 'event.complete',
    reason: 'Event completed.',
    guard: (event) => guardCompletable(event.status),
    statusUpdate: (serverTimestamp) => ({ status: 'completed', updatedAt: serverTimestamp() }),
  });

  return { eventId, status };
});
