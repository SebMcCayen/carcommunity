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
 * - Identities always require verified Plus/Supporter or admin moderation.
 *   Full event details and RSVP remain free.
 *
 * ## Roster membership
 * The caller is included in their own roster: this surface answers "who
 * answered", and a viewer who RSVP'd is one of those people and expects to see
 * themselves grouped under the answer they gave. The Android detail screen shows
 * the caller's own answer twice over by design — once as the interactive RSVP
 * selector row (`myRsvp`, the answer *control*) and once as an ordinary roster
 * entry (a member of the "who's going" list) — so including the caller does not
 * duplicate any single widget.
 *
 * ## Blocking
 * Reuses the same `userBlocks/{blocker}/blocked/{blocked}` store live.listNearby
 * reads: any attendee the caller has blocked, OR who has blocked the caller, is
 * dropped from the returned list. The public `rsvpCounts` tally on the event doc
 * is NOT adjusted here — it is a separate server tally and lives on the event doc.
 *
 * ## Bounding
 * A local car-community event will not have a huge roster, but the read is still
 * capped at `MAX_ATTENDEES` RSVP docs so it can never unbounded-scan. User docs
 * are fetched with a chunked `getAll`, and the block check is resolved with a
 * BOUNDED number of round-trips — O(ceil(roster/READ_CHUNK)) per direction — via
 * the single-peer helper below, NOT one Firestore query per candidate uid.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldPath } from 'firebase-admin/firestore';
import type { DocumentReference } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { toProfileProjection, type ProfileProjection } from '../convoy/convoy-core';
import type { EventStatus } from './events-core';
import {
  assembleRoster,
  canViewAttendeeRoster,
  isRsvpStatus,
  parseListAttendeesInput,
  resolveCallerBlockSet,
  type AttendeeView,
  type RsvpEntry,
} from './listAttendees-core';
import { effectiveSubscriptionTierFromStoredRecord } from '../subscription/subscription-core';
import { canAccessAdminFeatures } from '../shared/access';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
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
  /**
   * True when the caller's tier does not grant the roster (a free Community
   * member): the `attendees` list is empty NOT because nobody answered but
   * because the names are a paid benefit. The client renders an upgrade prompt
   * off this flag rather than a "nobody answered" state. False for a paid tier
   * or an admin, who receive the real roster.
   */
  requiresPaid: boolean;
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
    const activeActor = await requireActiveActor(request);
    const actor = { ...activeActor, isAdmin: canAccessAdminFeatures(activeActor.state) };

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

    // Enforce paid access before reading RSVP identities, regardless of flags.
    {
      // Admins bypass the paid gate and never need a subscription lookup, so the
      // tier is resolved ONLY for a non-admin caller — an admin never triggers
      // the subscriptions/{uid} read.
      let allowed = actor.isAdmin;
      if (!allowed) {
        const subscriptionSnap = await db.collection('subscriptions').doc(actor.uid).get();
        const tier = effectiveSubscriptionTierFromStoredRecord(
          subscriptionSnap.exists ? subscriptionSnap.data() : null,
          actor.uid,
        );
        allowed = canViewAttendeeRoster(actor.isAdmin, tier);
      }
      if (!allowed) {
        return { attendees: [], requiresPaid: true };
      }
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
      // The caller is intentionally NOT skipped — a viewer who answered belongs
      // in the "who answered" roster like everyone else (see the file header).
      const rawStatus = doc.data()?.status;
      if (!isRsvpStatus(rawStatus)) continue;
      entries.push({ userId: doc.id, status: rawStatus });
    }

    if (entries.length === 0) {
      return { attendees: [], requiresPaid: false };
    }

    const uids = entries.map((entry) => entry.userId);

    // Join identities (users/{uid}) in a chunked getAll, and resolve the block
    // set against the single caller peer — both in parallel.
    const [profiles, blockedSet] = await Promise.all([
      loadProfiles(uids),
      resolveCallerBlockSet(
        uids,
        READ_CHUNK,
        // caller → candidate: one blocker, many blocked → `documentId() in` query.
        (candidates) => queryBlockedSubset(actor.uid, candidates),
        // candidate → caller: distinct blockers → batched point-doc getAll.
        (candidates) => queryBlockersOf(actor.uid, candidates),
      ),
    ]);

    const attendees = assembleRoster(entries, profiles, (uid) => blockedSet.has(uid));

    return { attendees, requiresPaid: false };
  },
);

/**
 * Subset of `candidateUids` who have blocked `subjectUid`, resolved with ONE
 * batched `getAll` of the point docs `userBlocks/{candidate}/blocked/{subject}`
 * — a single streamed RPC for the whole chunk, versus one query per candidate.
 * The blocker uid is recovered from each hit's path (getAll does not guarantee
 * result order), so no index-alignment assumption is made.
 */
async function queryBlockersOf(subjectUid: string, candidateUids: string[]): Promise<string[]> {
  if (candidateUids.length === 0) return [];
  const refs: DocumentReference[] = candidateUids.map((candidate) =>
    db.collection('userBlocks').doc(candidate).collection('blocked').doc(subjectUid),
  );
  const snaps = await db.getAll(...refs);
  const blockers: string[] = [];
  for (const snap of snaps) {
    if (!snap.exists) continue;
    const blockerUid = snap.ref.parent.parent?.id; // userBlocks/{candidate}
    if (blockerUid) blockers.push(blockerUid);
  }
  return blockers;
}

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
