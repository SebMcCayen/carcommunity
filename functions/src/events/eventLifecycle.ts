/**
 * events.publish / events.cancel / events.complete
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-publish`,
 * `events-cancel`, and `events-complete`.
 *
 * publish and cancel require an active admin via requireAdminActor: the
 * server-managed `admin` custom claim plus a non-suspended, non-deleted
 * Firestore `users/{uid}` state with role admin or owner. complete is
 * creator-or-admin (see below).
 *
 * Status transitions mirror the legacy event-service:
 * - publish: draft only; requires title + approximateArea; start must not be
 *   in the past.
 * - cancel: draft or published; requires a reason; sets cancelledAt; never
 *   hard-deletes.
 * - complete: published only; callable by an admin OR the member who created
 *   the event (guardCompleteActor); going-RSVP attendees receive badge
 *   attendance credit (first_event / five_events, Phase 9f). Events also reach
 *   `completed` unattended via the scheduled auto-close sweep (scheduled.ts).
 *
 * Each ADMIN transition writes an immutable adminAuditEvents record in the
 * same transaction that changes the status. A member completing their own
 * event writes no audit record — adminAuditEvents stays a log of admin
 * actions (manageEvent.ts follows the same rule for member-created events).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { requireMemberOrAdminActor } from '../shared/memberActor';
import { recordEventAttendance } from '../badges/awards';
import { logger } from 'firebase-functions';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  guardCancellable,
  guardCompletable,
  guardCompleteActor,
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
  createdByUserId?: string | null;
}

/**
 * Credits badge attendance for every going-RSVP attendee of a just-completed
 * event (first_event / five_events, Phase 9f).
 *
 * Shared by BOTH completion paths — the events.complete callable and the
 * scheduled auto-close sweep — so "a completed event credits its attendees"
 * holds however the event reached `completed`, rather than depending on an
 * admin having clicked the button.
 *
 * CALLER CONTRACT: call this only after YOUR OWN write has just moved the
 * event published→completed inside a transaction. That is what makes the
 * credit single-shot — `completed` is terminal, so exactly one writer can ever
 * make that transition and only that writer credits. The two paths establish
 * it differently, and neither is a guard the other shares:
 * - events.complete — `guardCompletable` rejects any status but `published`,
 *   inside the transitionEvent transaction.
 * - the auto-close sweep — `closeEvent` re-reads the status inside its own
 *   transaction and returns false without writing if it is no longer
 *   `published`; the sweep credits only when it returned true.
 *
 * Calling this WITHOUT having performed that transition (e.g. on an
 * already-completed event) would double-credit — nothing in this function
 * detects that. Failures log per attendee and never propagate: attendance
 * credit must not undo a completion.
 */
export async function creditEventAttendance(eventId: string): Promise<void> {
  try {
    const goingRsvps = await db
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .where('status', '==', 'going')
      .get();
    // Parallel per-attendee writes (independent per-user documents) keep a
    // large attendee list well inside the caller's timeout; individual
    // failures log per user and never fail the completion.
    const results = await Promise.allSettled(
      goingRsvps.docs.map((rsvp) => recordEventAttendance(rsvp.id)),
    );
    results.forEach((result, index) => {
      if (result.status === 'rejected') {
        logger.error('Attendance credit failed for attendee', {
          eventId,
          uid: goingRsvps.docs[index]?.id,
          error: String(result.reason),
        });
      }
    });
  } catch (error) {
    logger.error('Event badge attendance recording failed', {
      eventId,
      error: String(error),
    });
  }
}

/**
 * Shared transition runner: guard → status update → audit record, atomically.
 *
 * `audit` (default true) writes the immutable adminAuditEvents record in the
 * same transaction. It is false ONLY for a non-admin actor: adminAuditEvents
 * stays a record of ADMIN actions, so a member ending their own event must
 * never write their uid into an `adminId` field (same rule manageEvent.create
 * follows for member-created events — a member action is attributed on the
 * event document itself, via createdByUserId).
 */
async function transitionEvent(params: {
  eventId: string;
  actorUid: string;
  action: 'event.publish' | 'event.cancel' | 'event.complete';
  reason: string;
  guard: (event: StoredEvent) => GuardResult;
  statusUpdate: (serverTimestamp: () => unknown) => Record<string, unknown>;
  auditDetails?: (event: StoredEvent) => Record<string, unknown>;
  audit?: boolean;
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
    if (params.audit ?? true) {
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
    }

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
  // Creator-or-admin: an active member may end an event they created, an
  // admin may end any (guardCompleteActor). requireMemberOrAdminActor rejects
  // suspended/deleted/non-member callers before ownership is even considered.
  const actor = await requireMemberOrAdminActor(request);

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
    guard: (event) => {
      const actorGuard = guardCompleteActor(
        { uid: actor.uid, isAdmin: actor.isAdmin },
        { createdByUserId: event.createdByUserId },
      );
      if (!actorGuard.ok) {
        return actorGuard;
      }
      return guardCompletable(event.status);
    },
    statusUpdate: (serverTimestamp) => ({ status: 'completed', updatedAt: serverTimestamp() }),
    audit: actor.isAdmin,
  });

  // Badge attendance (Phase 9f, legacy parity) — shared with the auto-close
  // sweep so completion always credits attendees.
  await creditEventAttendance(eventId);

  return { eventId, status };
});
