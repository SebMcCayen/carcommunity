/**
 * Shared convoy-chat membership gate.
 *
 * Extracted from convoyChat.ts so convoyChat.post / convoyChat.list and
 * chatchannels.reportMessage all decide "may this caller touch this convoy's
 * chat?" with ONE implementation — a report callable that admitted a caller the
 * post/list callables reject (or vice versa) would be a real hole, not a style
 * problem. Mirrors events/chatParticipant.ts, which exists for the same reason.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import {
  CONVOY_NOT_FOUND_MESSAGE,
  NOT_CONVOY_MEMBER_MESSAGE,
  isAcceptedConvoyMember,
} from './chat-core';

/**
 * Loads the convoy doc and asserts the caller is an ACCEPTED member. Not-found
 * (never permission-denied) for a missing convoy OR a total outsider so a
 * convoy can't be probed; a member of the convoy who hasn't accepted gets a
 * distinct failed-precondition.
 *
 * Returns the convoy data so a caller that also needs it — post's notification
 * fan-out reads the accepted-member list off it — costs no second read.
 */
export async function requireAcceptedConvoyMember(
  convoyId: string,
  uid: string,
): Promise<Record<string, unknown>> {
  const snap = await db.collection('convoys').doc(convoyId).get();
  if (!snap.exists) {
    throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
  }
  if (!isAcceptedConvoyMember(snap.data(), uid)) {
    const memberUids = Array.isArray(snap.data()?.memberUids)
      ? (snap.data()!.memberUids as unknown[])
      : [];
    if (memberUids.includes(uid)) {
      throw new HttpsError('failed-precondition', NOT_CONVOY_MEMBER_MESSAGE);
    }
    throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
  }
  return snap.data() ?? {};
}
