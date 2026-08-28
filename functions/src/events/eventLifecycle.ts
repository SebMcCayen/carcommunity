/**
 * events.publish / events.cancel / events.complete
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-publish`,
 * `events-cancel`, and `events-complete`.
 *
 * publish requires an active admin via requireAdminActor: the server-managed
 * `admin` custom claim plus a non-suspended, non-deleted Firestore
 * `users/{uid}` state with role admin or owner. cancel and complete are both
 * creator-or-admin (see below).
 *
 * Status transitions mirror the legacy event-service:
 * - publish: draft only; requires title + approximateArea; start must not be
 *   in the past.
 * - cancel: draft or published; requires a reason; sets cancelledAt; never
 *   hard-deletes. Callable by an admin/owner OR the member who created the
 *   event (guardManageEventActor); a member cancelling their own writes no
 *   audit record. The `event_cancelled` fan-out (events-onEventCancelled)
 *   notifies going-RSVP attendees on the transition into `cancelled`.
 * - complete: published only; callable by an admin OR the member who created
 *   the event (guardCompleteActor). Completion NO LONGER credits attendance:
 *   the first_event / five_events / Träffräv badge counts VERIFIED check-ins
 *   (points-onAttendanceVerified → creditVerifiedEventAttendance), not an RSVP,
 *   so a member earns it by being at the meet rather than by tapping "going".
 *   Events also reach `completed` unattended via the scheduled auto-close sweep
 *   (scheduled.ts).
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
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  guardCancellable,
  guardCompletable,
  guardCompleteActor,
  guardManageEventActor,
  guardPublishable,
  parseCancelEventInput,
  parseEventIdInput,
  type EventStatus,
  type GuardResult,
} from './events-core';
import type { EventIdResponse } from './manageEvent';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
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
  // Creator-or-admin: an active member may cancel an event THEY created, an
  // admin may cancel any (guardManageEventActor). requireMemberOrAdminActor
  // rejects suspended/deleted/non-member callers before ownership is even
  // considered — same shape as complete() below.
  const actor = await requireMemberOrAdminActor(request);

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
    guard: (event) => {
      const actorGuard = guardManageEventActor(
        { uid: actor.uid, isAdmin: actor.isAdmin },
        { createdByUserId: event.createdByUserId },
      );
      if (!actorGuard.ok) {
        return actorGuard;
      }
      return guardCancellable(event.status);
    },
    statusUpdate: (serverTimestamp) => ({
      status: 'cancelled',
      cancelledAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    }),
    // A member cancelling their OWN event writes no adminAuditEvents record —
    // that log stays a record of admin actions (same rule complete() follows).
    audit: actor.isAdmin,
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

  // Completion does NOT credit attendance: the badge counts VERIFIED check-ins
  // (points-onAttendanceVerified), not RSVPs, so ending the event awards
  // nobody a badge who was not measurably present.
  return { eventId, status };
});
