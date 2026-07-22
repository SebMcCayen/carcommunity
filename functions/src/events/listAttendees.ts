/**
 * events.listAttendees — member-readable roster of who RSVP'd to a published
 * event, with each member's answer (going / maybe / not_going).
 *
 * Deployed via the `events` export group as `events-listAttendees`
 * (europe-west1, contracts/functions/functions.json).
 *
 * ## Why a callable rather than loosening the rsvps read rule
 * `events/{eventId}/rsvps/{userId}` is `{ status, updatedAt }` with NO identity
 * field, and its read rule is owner-or-admin. A rule loosening would (a) still
 * force the client to do N `users/{uid}` joins to turn uids into names/avatars,
 * and (b) push the block filtering onto the client. This callable does the
 * identity join server-side once via the Admin SDK (which bypasses rules — so
 * the owner-or-admin read rule stays untouched) and applies blocking centrally.
 *
 * ## Gating
 * - Caller must be an active member OR an admin (`requireMemberOrAdminActor`);
 *   suspended/deleted accounts are always rejected.
 * - Only PUBLISHED events expose a roster. Draft / cancelled / completed events
 *   return `not-found`, mirroring the teaser-doc read rule (a draft is not
 *   member-visible at all) and avoiding any leak of a non-public attendee list.
 *
 * ## Blocking
 * Reuses the convoy block matrix (`resolvePeerBlockPairs` / the same
 * `userBlocks/{blocker}/blocked/{blocked}` store live.listNearby reads): any
 * attendee the caller has blocked, OR who has blocked the caller, is dropped
 * from the returned list. The public `rsvpCounts` tally on the event doc is NOT
 * adjusted here — it is a separate server tally and lives on the event doc.
 *
 * ## Bounding
 * A local car-community event will not have a huge roster, but the read is still
 * capped at `MAX_ATTENDEES` RSVP docs so it can never unbounded-scan. User docs
 * are fetched with a single chunked `getAll`, and the block matrix is resolved
 * with reads that grow with the roster size, not roster×caller.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldPath } from 'firebase-admin/firestore';
import type { DocumentReference } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberOrAdminActor } from '../shared/memberActor';
import {
  isBlockedAgainstAnyPeer,
  resolvePeerBlockPairs,
  toProfileProjection,
  type ProfileProjection,
} from '../convoy/convoy-core';
import type { EventStatus } from './events-core';
import {
  assembleRoster,
  isRsvpStatus,
  parseListAttendeesInput,
  type AttendeeView,
  type RsvpEntry,
} from './listAttendees-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/**
 * Upper bound on RSVP docs read. A club car meet is nowhere near this; the cap
 * only guarantees the callable can never unbounded-scan a pathological event.
 */
const MAX_ATTENDEES = 1000;

/** Chunk size for `getAll` / `documentId() in [...]` — Firestore caps `in` at 30. */
const READ_CHUNK = 30;

export interface ListAttendeesResponse {
  attendees: AttendeeView[];
}

function chunk<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    chunks.push(items.slice(i, i + size));
  }
  return chunks;
}

/** Uids this blocker has blocked, out of `blockedUids` (<= READ_CHUNK). */
async function queryBlockedSubset(blockerUid: string, blockedUids: string[]): Promise<string[]> {
  const snap = await db
    .collection('userBlocks')
    .doc(blockerUid)
    .collection('blocked')
    .where(FieldPath.documentId(), 'in', blockedUids)
    .get();
  return snap.docs.map((doc) => doc.id);
}

export const listAttendees = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ListAttendeesResponse> => {
    const actor = await requireMemberOrAdminActor(request);

    const parsed = parseListAttendeesInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { eventId } = parsed.input;

    const eventSnap = await db.collection('events').doc(eventId).get();
    if (!eventSnap.exists) {
      throw new HttpsError('not-found', 'Event not found.');
    }
    const status = eventSnap.data()?.status as EventStatus | undefined;
    if (status !== 'published') {
      // Draft/cancelled/completed rosters are not exposed — treat as absent so
      // the callable never leaks who answered a non-public event.
      throw new HttpsError('not-found', 'Event not found.');
    }

    // Bounded read of the RSVP subcollection. Only rows with a canonical status
    // are kept (a malformed doc is ignored, not surfaced as a mystery row).
    const rsvpSnap = await db
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .limit(MAX_ATTENDEES)
      .get();

    const entries: RsvpEntry[] = [];
    for (const doc of rsvpSnap.docs) {
      if (doc.id === actor.uid) continue; // the caller sees their own RSVP elsewhere
      const rawStatus = doc.data()?.status;
      if (!isRsvpStatus(rawStatus)) continue;
      entries.push({ userId: doc.id, status: rawStatus });
    }

    if (entries.length === 0) {
      return { attendees: [] };
    }

    const uids = entries.map((entry) => entry.userId);

    // Join identities (users/{uid}) in one chunked getAll, and resolve the block
    // matrix against the single caller peer — both in parallel.
    const [profiles, blockPairs] = await Promise.all([
      loadProfiles(uids),
      resolvePeerBlockPairs(uids, [actor.uid], queryBlockedSubset),
    ]);

    const attendees = assembleRoster(entries, profiles, (uid) =>
      isBlockedAgainstAnyPeer(uid, [actor.uid], blockPairs),
    );

    return { attendees };
  },
);

/** Batched users/{uid} projection read. Absent doc → entry omitted (deleted user). */
async function loadProfiles(uids: string[]): Promise<Map<string, ProfileProjection>> {
  const profiles = new Map<string, ProfileProjection>();
  for (const group of chunk(uids, READ_CHUNK)) {
    const refs: DocumentReference[] = group.map((uid) => db.collection('users').doc(uid));
    const snaps = await db.getAll(...refs);
    for (const snap of snaps) {
      if (!snap.exists) continue; // deleted / never-provisioned → skipped by assembleRoster
      profiles.set(snap.id, toProfileProjection(snap.data()));
    }
  }
  return profiles;
}
