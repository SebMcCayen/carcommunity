/**
 * groupDrive.join / updateStatus / leave — member callables
 * (contracts/functions/functions.json), Phase 11.
 *
 * Legacy group-drive-service parity on
 * events/{eventId}/groupDriveParticipants/{uid}:
 * - join: published event + RSVP going|maybe + not ended; idempotent;
 *   rejoin resets joinedAt and clears leftAt. displayName denormalized
 *   for the roster.
 * - updateStatus: joined|on_the_way|arrived on an active participation.
 * - leave: idempotent; does NOT stop the live location session.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor, requireActiveActor } from '../shared/memberActor';
import {
  buildParticipantDocument,
  guardJoinableEvent,
  parseJoinGroupDriveInput,
  parseLeaveGroupDriveInput,
  parseUpdateDriveStatusInput,
} from './groupdrive-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const participantRef = (eventId: string, uid: string) =>
  db.collection('events').doc(eventId).collection('groupDriveParticipants').doc(uid);

export interface ParticipantResponse {
  eventId: string;
  status: string;
  rejoined?: boolean;
}

export const join = onCall(CALLABLE_OPTS, async (request): Promise<ParticipantResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseJoinGroupDriveInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { eventId } = parsed.input;

  const [eventSnap, rsvpSnap, profileSnap] = await Promise.all([
    db.collection('events').doc(eventId).get(),
    db.collection('events').doc(eventId).collection('rsvps').doc(actor.uid).get(),
    db.collection('users').doc(actor.uid).get(),
  ]);
  if (!eventSnap.exists) {
    throw new HttpsError('not-found', 'Event not found.');
  }
  const event = eventSnap.data()!;
  const guard = guardJoinableEvent({
    eventStatus: event.status as string,
    endsAt: event.endsAt?.toDate?.() ?? null,
    rsvpStatus: (rsvpSnap.data()?.status as string | undefined) ?? null,
    now: new Date(),
  });
  if (!guard.ok) {
    throw new HttpsError(guard.code, guard.message);
  }

  const displayName = (profileSnap.data()?.displayName as string | undefined) ?? null;
  const ref = participantRef(eventId, actor.uid);
  const rejoined = await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (!existing.exists) {
      tx.set(ref, buildParticipantDocument(displayName, () => FieldValue.serverTimestamp()));
      return false;
    }
    if (existing.data()!.status === 'left') {
      tx.update(ref, {
        status: 'joined',
        joinedAt: FieldValue.serverTimestamp(),
        leftAt: null,
        displayName,
        updatedAt: FieldValue.serverTimestamp(),
      });
      return true;
    }
    return false; // already active — idempotent
  });

  return { eventId, status: 'joined', rejoined };
});

export const updateStatus = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ParticipantResponse> => {
    const actor = await requireMemberActor(request);

    const parsed = parseUpdateDriveStatusInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { eventId, status } = parsed.input;
    const ref = participantRef(eventId, actor.uid);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists || snap.data()!.status === 'left') {
        throw new HttpsError('failed-precondition', 'Not an active group drive participant.');
      }
      tx.update(ref, { status, updatedAt: FieldValue.serverTimestamp() });
    });

    return { eventId, status };
  },
);

export const leave = onCall(CALLABLE_OPTS, async (request): Promise<ParticipantResponse> => {
  // Leaving must work even if the entitlement lapsed mid-drive.
  const actor = await requireActiveActor(request);

  const parsed = parseLeaveGroupDriveInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { eventId } = parsed.input;
  const ref = participantRef(eventId, actor.uid);

  // Idempotent: leaving when absent or already left succeeds quietly.
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.data()!.status === 'left') {
      return;
    }
    tx.update(ref, {
      status: 'left',
      leftAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
  });

  return { eventId, status: 'left' };
});
