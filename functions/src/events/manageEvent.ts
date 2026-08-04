/**
 * events.create (member or admin) / events.update (admin only)
 * (contracts/functions/functions.json).
 *
 * Deployed via the `events` export group as `events-create` and
 * `events-update`.
 *
 * events.update requires an active admin via requireAdminActor: the
 * server-managed `admin` custom claim plus a non-suspended, non-deleted
 * Firestore `users/{uid}` state with role admin or owner.
 *
 * events.create also accepts an active MEMBER (requireMemberOrAdminActor —
 * suspended/deleted/non-member callers are rejected). Creator role decides the
 * outcome; see events-core.ts [EVENT_CREATOR_ROLES] for the moderation
 * rationale and the rejected alternative:
 * - admin  → `draft` + an adminAuditEvents record; an admin publishes later.
 * - member → `published` immediately, `isOfficial` forced false,
 *   `createdByRole: 'member'` + `createdByUserId` for attribution, and no more
 *   than MEMBER_EVENT_RATE_LIMIT_MAX per rolling 24h. Admins moderate after
 *   the fact through the existing audited events.cancel / events.update.
 * The adminAuditEvents log stays a record of ADMIN actions only — a member
 * creation is attributed on the event document itself, never by writing a
 * member uid into an `adminId` field.
 *
 * Every event is stored as two documents (see events-core.ts): the
 * teaser-safe `events/{eventId}` and the member-gated
 * `events/{eventId}/details/private`. Both are written atomically (with the
 * admin audit record, when there is one) — legacy event-service parity
 * (audit actions `event.create` / `event.update` with changedFields).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { requireMemberOrAdminActor } from '../shared/memberActor';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  buildEventDocuments,
  buildEventUpdates,
  guardCoordinatePair,
  guardEventTimes,
  guardPublishable,
  guardUpdatableStatus,
  initialEventStatus,
  isMemberEventRateLimited,
  memberEventRateLimitWindowStart,
  parseCreateEventInput,
  parseUpdateEventInput,
  stockholmEndOfDay,
  type EventStatus,
} from './events-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface EventIdResponse {
  eventId: string;
  status: EventStatus;
}

export const create = onCall(CALLABLE_OPTS, async (request): Promise<EventIdResponse> => {
  const actor = await requireMemberOrAdminActor(request);
  const creatorRole = actor.isAdmin ? 'admin' : 'member';

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

  const status = initialEventStatus(creatorRole);
  // A member-created event skips the admin `events.publish` step, so it must
  // still clear the same publish preconditions that callable enforces —
  // otherwise creation would be a back door to a published event with a start
  // time in the past (guardEventTimes alone never checks that).
  if (status === 'published') {
    const publishGuard = guardPublishable(
      {
        status: 'draft',
        title: input.title,
        startsAt: new Date(input.startsAt),
      },
      new Date(),
    );
    if (!publishGuard.ok) {
      throw new HttpsError(publishGuard.code, publishGuard.message);
    }
  }

  const serverTimestamp = () => FieldValue.serverTimestamp();
  const { eventDoc, privateDoc } = buildEventDocuments(
    input,
    actor.uid,
    serverTimestamp,
    creatorRole,
  );

  const events = db.collection('events');
  const eventRef = events.doc();
  const windowStart = memberEventRateLimitWindowStart(new Date());

  await db.runTransaction(async (tx) => {
    // Per-member creation cap (mirrors the feedback.reportIssue limiter):
    // counted inside the transaction so concurrent submits cannot race past
    // it. Admins are exempt — the audited admin path is the trusted one.
    //
    // The count filters on `createdByRole: 'member'` as well as the uid: the
    // cap is on MEMBER-created events, so events this uid created while an
    // admin must not count against them if they later create as a member.
    // (Backed by the events composite index
    // `createdByUserId ASC, createdByRole ASC, createdAt ASC` — the equality
    // filters precede the range on createdAt.)
    if (creatorRole === 'member') {
      const recent = await tx.get(
        events
          .where('createdByUserId', '==', actor.uid)
          .where('createdByRole', '==', 'member')
          .where('createdAt', '>=', windowStart)
          .count(),
      );
      if (isMemberEventRateLimited(recent.data().count)) {
        throw new HttpsError(
          'resource-exhausted',
          'Too many events created — please wait a while before creating another.',
        );
      }
    }

    tx.set(eventRef, eventDoc);
    tx.set(eventRef.collection('details').doc('private'), privateDoc);
    if (creatorRole === 'admin') {
      tx.set(
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
    }
  });

  return { eventId: eventRef.id, status };
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

    // When the times are edited but the resulting event has a start and no
    // explicit end, default endsAt to the Europe/Stockholm end-of-day of the
    // effective start (mirrors the create-time default in buildEventDocuments).
    const timesTouched = input.startsAt !== undefined || input.endsAt !== undefined;
    if (timesTouched && effectiveEndsAt === null) {
      eventDoc.endsAt = new Date(stockholmEndOfDay(effectiveStartsAt));
      if (!changedFields.includes('endsAt')) {
        changedFields.push('endsAt');
      }
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
