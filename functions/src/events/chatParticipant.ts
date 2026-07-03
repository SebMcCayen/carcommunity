/**
 * Shared authorization context for member chat callables (Phase 9c).
 *
 * Loads the caller's backend-managed access state, the event status, and the
 * caller's RSVP in one place, then applies the legacy eligibility predicate
 * (active member + published event + going/maybe RSVP). Backend state is the
 * source of truth — client-supplied claims are only a fast-reject.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { toUserAccessState, type UserAccessState } from '../shared/access';
import { guardChatParticipant } from './chat-core';
import type { EventStatus } from './events-core';

export interface ChatParticipant {
  uid: string;
  state: UserAccessState;
  /** Display name from users/{uid} — denormalized onto messages. */
  displayName: string;
}

/**
 * Asserts the caller may participate in the chat of `eventId`. Throws
 * HttpsError with codes from contracts/errors/errors.json.
 */
export async function requireChatParticipant(
  request: CallableRequest,
  eventId: string,
): Promise<ChatParticipant> {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError('unauthenticated', 'Sign in to use event chat.');
  }
  const uid = auth.uid;

  const [userSnap, eventSnap, rsvpSnap] = await Promise.all([
    db.collection('users').doc(uid).get(),
    db.collection('events').doc(eventId).get(),
    db.collection('events').doc(eventId).collection('rsvps').doc(uid).get(),
  ]);

  if (!eventSnap.exists) {
    throw new HttpsError('not-found', 'Event not found.');
  }

  const state = toUserAccessState(userSnap.data());
  const guard = guardChatParticipant({
    state,
    eventStatus: eventSnap.data()?.status as EventStatus | undefined,
    rsvpStatus: rsvpSnap.data()?.status as string | undefined,
  });
  if (!guard.ok) {
    throw new HttpsError(guard.code, guard.message);
  }

  const displayName =
    typeof userSnap.data()?.displayName === 'string' ? (userSnap.data()!.displayName as string) : '';

  return { uid, state, displayName };
}
