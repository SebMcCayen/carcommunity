/**
 * convoy.create / convoy.respond / convoy.start / convoy.end / convoy.list /
 * convoy.leave / convoy.invite / convoy.setDestination / convoy.clearDestination
 * — member-gated convoy callables (contracts/functions/functions.json).
 *
 * Deployed via the `convoy` export group (functions/src/index.ts) as
 * `convoy-create`, `convoy-respond`, `convoy-start`, `convoy-end`,
 * `convoy-list`, `convoy-leave`, `convoy-invite`, `convoy-setDestination`,
 * `convoy-clearDestination`. This is the convoy FOUNDATION (sessions + invites
 * + membership + positions + shared destination + summary); the 3-channel chat
 * is a SEPARATE domain (chatchannels/convoyChat.ts).
 *
 * Invariants:
 *  - Backend is the sole writer of convoys (firebase/firestore.rules grants
 *    member reads via memberUids, denies all client writes).
 *  - Only FRIENDS of the INVITER (users/{inviter}/friends/{uid}) may be invited
 *    — at create AND at convoy.invite; blocking is honoured in BOTH directions,
 *    against the inviter and (for convoy.invite) against every existing
 *    accepted member. Non-friend / blocked / missing invitees are silently
 *    SKIPPED (reported in the response), never surfaced as an error that would
 *    reveal a block.
 *  - Membership CHANGES are member-driven but bounded: any accepted member may
 *    invite (friend-gated), a non-owner accepted member may leave, and the
 *    convoy is capped at MAX_CONVOY_SIZE. The OWNER may not leave — they end.
 *  - Member actions here write NO adminAuditEvents: that log is admin-only.
 *  - Membership + lifecycle transitions (forming → active → ended) run through
 *    these callables so the server owns them; the summary is computed + stored
 *    on convoy.end and is readable by ALL members.
 *  - LIVE POSITIONS reuse the live-location domain: the response carries
 *    livePositionUids (accepted members) and the client subscribes to RTDB
 *    liveLocation/{uid}/latest for each — the convoy never duplicates GPS
 *    storage (see convoy-core.ts).
 *  - On invite, a best-effort in-app notification is written for each invitee
 *    (reusing writeInAppNotification) under the dedicated 'convoy_invite'
 *    category, so invitees can opt out of convoy invites without silencing
 *    every 'system_notice'. writeInAppNotification checks that preference
 *    before writing. An 'open_convoy' action type remains a follow-up; the
 *    notification still deep-links via 'open_notifications'.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import { writeInAppNotification } from '../notifications/deliver';
import {
  CONVOY_ALREADY_ENDED_MESSAGE,
  CONVOY_ENDED_MESSAGE,
  CONVOY_NOT_FORMING_MESSAGE,
  CONVOY_NOT_FOUND_MESSAGE,
  INVITE_ALREADY_HANDLED_MESSAGE,
  NOT_INVITED_MESSAGE,
  NO_VALID_INVITEES_MESSAGE,
  OWNER_CANNOT_LEAVE_MESSAGE,
  NOT_ACCEPTED_MEMBER_MESSAGE,
  CONVOY_FULL_MESSAGE,
  DESTINATION_CLEAR_FORBIDDEN_MESSAGE,
  MAX_CONVOYS_RETURNED,
  MAX_CONVOY_SIZE,
  acceptedMemberUids,
  buildConvoyDocument,
  buildLeaveConvoyUpdate,
  buildMemberEntry,
  computeConvoySummary,
  isAcceptedConvoyMember,
  isConvoyMember,
  memberEntry,
  parseConvoyIdInput,
  parseCreateConvoyInput,
  parseInviteToConvoyInput,
  parseListConvoysInput,
  parseRespondConvoyInput,
  parseSetConvoyDestinationInput,
  toConvoyDestination,
  toConvoySummary,
  toProfileProjection,
  type ConvoySummary,
  type ProfileProjection,
  type SkippedInvitee,
} from './convoy-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

// ---------------------------------------------------------------------------
// Firestore references
// ---------------------------------------------------------------------------

function convoyRef(convoyId: string) {
  return db.collection('convoys').doc(convoyId);
}

function friendshipRef(ownerUid: string, friendUid: string) {
  return db.collection('users').doc(ownerUid).collection('friends').doc(friendUid);
}

function blockRef(blockerUid: string, blockedUid: string) {
  return db.collection('userBlocks').doc(blockerUid).collection('blocked').doc(blockedUid);
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Converts a stored Firestore value to an ISO string, or null. */
function toIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

/** Converts a stored Firestore value to a Date, or null (summary computation). */
function toDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

/**
 * Reads a users/{uid} profile projection. Returns null when the user is missing
 * or fails the member gate — soft-deleted OR suspended OR (while gating is
 * enabled) not an active member. Member gating is currently DISABLED (see
 * shared/memberGating.ts), so today only missing/suspended/deleted users are
 * skipped. When gating is re-enabled this again also skips non-members: every
 * convoy callable is member-gated, so a non-member invitee could never accept /
 * decline / see the convoy; treating them as null here means they are skipped
 * (as not_found) rather than written into memberUids/members and notified.
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || !memberGateAllows(toUserAccessState(snap.data()))) {
    return null;
  }
  return toProfileProjection(snap.data());
}

// ---------------------------------------------------------------------------
// Shared invitee resolution (convoy.create + convoy.invite)
// ---------------------------------------------------------------------------

/**
 * Validates a requested invitee list against the SAME gate for both
 * convoy.create and convoy.invite: each candidate must be an established friend
 * of the INVITER (users/{inviter}/friends/{uid}), unblocked in both directions,
 * an existing non-restricted account, not the inviter themselves, not listed
 * twice, and not already in the convoy.
 *
 * The friend edge is checked against the INVITER, not the convoy owner. For
 * create those are the same person; for invite they need not be, and requiring
 * the owner's friendship would mean a member could only add people the owner
 * already knows — which defeats the point of letting members grow the group.
 *
 * `blockPeerUids` is the set of EXISTING members the candidate must also be
 * unblocked against (both directions). Without it, a member could drag someone
 * into a convoy with a person who had blocked them — the block would hold for
 * DMs and live positions while the two sat in the same convoy chat and roster.
 * The reason is still the neutral `not_found`, so nothing about who blocked
 * whom leaks to the inviter.
 *
 * Per-invitee reads run concurrently (index-aligned outcomes), but the output
 * arrays are assembled sequentially in REQUEST order, which clients rely on.
 * The self/duplicate/already-member classification runs in each task's
 * synchronous prologue (before the first await), so `seen` is populated in
 * request order too.
 */
type InviteeOutcome =
  | { kind: 'invited'; uid: string; profile: ProfileProjection }
  | { kind: 'skipped'; skip: SkippedInvitee };

async function resolveInvitees(
  inviterUid: string,
  requestedUids: string[],
  existingMemberUids: string[],
  blockPeerUids: string[],
): Promise<{ invited: Array<{ uid: string; profile: ProfileProjection }>; skipped: SkippedInvitee[] }> {
  const existing = new Set(existingMemberUids);
  const seen = new Set<string>();

  const outcomes = await Promise.all(
    requestedUids.map(async (uid): Promise<InviteeOutcome> => {
      if (uid === inviterUid) {
        return { kind: 'skipped', skip: { uid, reason: 'self' } };
      }
      if (seen.has(uid)) {
        return { kind: 'skipped', skip: { uid, reason: 'duplicate' } };
      }
      seen.add(uid);
      if (existing.has(uid)) {
        return { kind: 'skipped', skip: { uid, reason: 'already_member' } };
      }

      const [friendSnap, inviterBlockedThem, theyBlockedInviter, profile, peerBlocks] =
        await Promise.all([
          friendshipRef(inviterUid, uid).get(),
          blockRef(inviterUid, uid).get(),
          blockRef(uid, inviterUid).get(),
          loadProfile(uid),
          Promise.all(
            blockPeerUids.flatMap((peerUid) => [
              blockRef(peerUid, uid).get(),
              blockRef(uid, peerUid).get(),
            ]),
          ),
        ]);

      if (
        inviterBlockedThem.exists ||
        theyBlockedInviter.exists ||
        peerBlocks.some((snap) => snap.exists)
      ) {
        // Neutral reason — never reveals a block edge (privacy parity with
        // friends/dm, which never distinguish who blocked whom in
        // client-visible results). A block-related skip is surfaced as
        // `not_found`, identical to a missing/non-member invitee, so the
        // inviter can't infer that either party blocked the other. The invitee
        // is still NOT added or notified.
        return { kind: 'skipped', skip: { uid, reason: 'not_found' } };
      }
      if (!friendSnap.exists) {
        return { kind: 'skipped', skip: { uid, reason: 'not_friend' } };
      }
      if (!profile) {
        return { kind: 'skipped', skip: { uid, reason: 'not_found' } };
      }
      return { kind: 'invited', uid, profile };
    }),
  );

  // Assemble outputs in request order (index-aligned to requestedUids).
  const invited: Array<{ uid: string; profile: ProfileProjection }> = [];
  const skipped: SkippedInvitee[] = [];
  for (const outcome of outcomes) {
    if (outcome.kind === 'invited') {
      invited.push({ uid: outcome.uid, profile: outcome.profile });
    } else {
      skipped.push(outcome.skip);
    }
  }
  return { invited, skipped };
}

/**
 * Best-effort in-app invite notification, identical for create and invite (one
 * path, not two). Never fails the mutation. The dedicated 'convoy_invite'
 * category is honoured per-recipient INSIDE writeInAppNotification, so an
 * invitee who disabled convoy invites is skipped there rather than filtered
 * here.
 */
async function notifyInvitees(
  inviteeUids: string[],
  inviterDisplayName: string | null,
  convoyId: string,
  convoyTitle: string | null,
): Promise<void> {
  const inviterName = inviterDisplayName ?? 'En vän';
  await Promise.all(
    inviteeUids.map((uid) =>
      writeInAppNotification(uid, {
        category: 'convoy_invite',
        title: 'Konvoj-inbjudan',
        previewText: `${inviterName} har bjudit in dig till en konvoj${
          convoyTitle ? `: ${convoyTitle}` : ''
        }.`,
        actionType: 'open_notifications',
        relatedEntityId: convoyId,
      }).catch(() => undefined),
    ),
  );
}

// ---------------------------------------------------------------------------
// convoy.create
// ---------------------------------------------------------------------------

export interface CreateConvoyResult {
  convoy: ConvoySummary;
  /** Uids actually invited (friend, unblocked, existing). */
  invited: string[];
  /** Requested uids that were skipped, with the reason. */
  skipped: SkippedInvitee[];
}

export const create = onCall(CALLABLE_OPTS, async (request): Promise<CreateConvoyResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseCreateConvoyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { title, inviteeUids } = parsed.input;

  const ownerProfile = await loadProfile(actor.uid);
  if (!ownerProfile) {
    // The actor gate already loaded users/{caller}; a missing profile here is
    // an inconsistent state rather than a client error.
    throw new HttpsError('failed-precondition', NO_VALID_INVITEES_MESSAGE);
  }

  // De-duplicate + validate the requested list against the shared invitee gate
  // (friend of the caller, unblocked both ways, existing non-restricted
  // account, not self, not listed twice). A brand-new convoy has no existing
  // members and no other members to check blocks against, so both peer sets are
  // empty here — which is the only difference from convoy.invite.
  const { invited, skipped } = await resolveInvitees(actor.uid, inviteeUids, [], []);

  if (invited.length === 0) {
    throw new HttpsError('failed-precondition', NO_VALID_INVITEES_MESSAGE);
  }

  // Let Firestore generate the convoy id directly on the target collection.
  const ref = db.collection('convoys').doc();
  const document = buildConvoyDocument(
    { ownerUid: actor.uid, title: title ?? null, ownerProfile, invitees: invited },
    () => FieldValue.serverTimestamp(),
  );
  await ref.set(document);

  // Best-effort in-app invite notifications (never fail the create) — the same
  // single path convoy.invite uses.
  await notifyInvitees(
    invited.map((i) => i.uid),
    ownerProfile.displayName,
    ref.id,
    title ?? null,
  );

  const fresh = await ref.get();
  return {
    convoy: toConvoySummary(ref.id, fresh.data() ?? {}, actor.uid, toIso),
    invited: invited.map((i) => i.uid),
    skipped,
  };
});

// ---------------------------------------------------------------------------
// convoy.respond (invitee accepts / declines)
// ---------------------------------------------------------------------------

export interface RespondConvoyResult {
  convoy: ConvoySummary;
  inviteStatus: 'accepted' | 'declined';
}

export const respond = onCall(CALLABLE_OPTS, async (request): Promise<RespondConvoyResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseRespondConvoyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { convoyId, action } = parsed.input;
  const ref = convoyRef(convoyId);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    // Not-found (never permission-denied) so a convoy can't be probed by a
    // non-member.
    if (!snap.exists || !isConvoyMember(snap.data(), actor.uid)) {
      throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
    }
    const data = snap.data()!;
    if (data.status === 'ended') {
      throw new HttpsError('failed-precondition', CONVOY_ENDED_MESSAGE);
    }
    const entry = memberEntry(data, actor.uid);
    // Only an invitee (member role) with a still-pending invite may respond;
    // the owner is not an invitee.
    if (!entry || entry.role !== 'member') {
      throw new HttpsError('failed-precondition', NOT_INVITED_MESSAGE);
    }
    if (entry.inviteStatus !== 'invited') {
      throw new HttpsError('failed-precondition', INVITE_ALREADY_HANDLED_MESSAGE);
    }

    const ts = FieldValue.serverTimestamp();
    const newStatus = action === 'accept' ? 'accepted' : 'declined';
    tx.set(
      ref,
      {
        members: {
          [actor.uid]: {
            inviteStatus: newStatus,
            joinedAt: action === 'accept' ? ts : null,
          },
        },
      },
      { merge: true },
    );
  });

  const fresh = await ref.get();
  return {
    convoy: toConvoySummary(convoyId, fresh.data() ?? {}, actor.uid, toIso),
    inviteStatus: action === 'accept' ? 'accepted' : 'declined',
  };
});

// ---------------------------------------------------------------------------
// convoy.start (owner: forming → active)
// ---------------------------------------------------------------------------

export const start = onCall(CALLABLE_OPTS, async (request): Promise<{ convoy: ConvoySummary }> => {
  const actor = await requireMemberActor(request);

  const parsed = parseConvoyIdInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const ref = convoyRef(parsed.input.convoyId);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    // Owner-only: a non-owner (including a member) gets not-found so a convoy
    // they don't own can't be probed.
    if (!snap.exists || snap.data()?.ownerUid !== actor.uid) {
      throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
    }
    if (snap.data()?.status !== 'forming') {
      throw new HttpsError('failed-precondition', CONVOY_NOT_FORMING_MESSAGE);
    }
    // Stamp with the function's local clock (NOT serverTimestamp) so start and
    // end use the SAME time source — convoy.end computes the stored duration
    // from startedAt→endedAt, and mixing a server timestamp here with a
    // local-clock end could clamp the duration to 0 under clock skew.
    tx.set(ref, { status: 'active', startedAt: Timestamp.fromDate(new Date()) }, { merge: true });
  });

  const fresh = await ref.get();
  return { convoy: toConvoySummary(parsed.input.convoyId, fresh.data() ?? {}, actor.uid, toIso) };
});

// ---------------------------------------------------------------------------
// convoy.end (owner: → ended + compute/store summary)
// ---------------------------------------------------------------------------

export const end = onCall(CALLABLE_OPTS, async (request): Promise<{ convoy: ConvoySummary }> => {
  const actor = await requireMemberActor(request);

  const parsed = parseConvoyIdInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const ref = convoyRef(parsed.input.convoyId);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || snap.data()?.ownerUid !== actor.uid) {
      throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
    }
    if (snap.data()?.status === 'ended') {
      throw new HttpsError('failed-precondition', CONVOY_ALREADY_ENDED_MESSAGE);
    }
    // Single local-clock instant used for BOTH the summary math and the stored
    // endedAt, matching convoy.start's local-clock startedAt so the duration is
    // computed from one coherent time source.
    const endedAt = Timestamp.fromDate(new Date());
    const summary = computeConvoySummary(snap.data()!, endedAt.toDate(), toDate);
    tx.set(ref, { status: 'ended', endedAt, summary }, { merge: true });
  });

  const fresh = await ref.get();
  return { convoy: toConvoySummary(parsed.input.convoyId, fresh.data() ?? {}, actor.uid, toIso) };
});

// ---------------------------------------------------------------------------
// convoy.list (caller's convoys + pending invites)
// ---------------------------------------------------------------------------

export interface ListConvoysResult {
  /** Every convoy the caller belongs to (owner or invitee), newest-first. */
  convoys: ConvoySummary[];
  /** Subset the caller still has a pending invite for (green-dot pending list). */
  pendingInvites: ConvoySummary[];
}

export const list = onCall(CALLABLE_OPTS, async (request): Promise<ListConvoysResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseListConvoysInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  // Bounded read: a caller can never be in more convoys than the cap without
  // paginating (a generous safety ceiling, matching the friends/dm convention).
  // orderBy createdAt desc BEFORE limit so a hit cap keeps the NEWEST convoys.
  // Needs the composite index [memberUids CONTAINS, createdAt DESC]
  // (firebase/firestore.indexes.json).
  const snap = await db
    .collection('convoys')
    .where('memberUids', 'array-contains', actor.uid)
    .orderBy('createdAt', 'desc')
    .limit(MAX_CONVOYS_RETURNED)
    .get();

  const convoys = snap.docs.map((doc) => toConvoySummary(doc.id, doc.data(), actor.uid, toIso));
  const pendingInvites = convoys.filter(
    (c) => c.status !== 'ended' && c.viewer?.inviteStatus === 'invited',
  );

  return { convoys, pendingInvites };
});

// ---------------------------------------------------------------------------
// convoy.leave (an accepted MEMBER removes themselves; the owner cannot)
// ---------------------------------------------------------------------------

export interface LeaveConvoyResult {
  /**
   * The convoy AFTER the caller left. `viewer` is null in this response — the
   * caller is no longer a member, and saying otherwise would be a lie the
   * client would render a roster from.
   */
  convoy: ConvoySummary;
  /** Accepted members still in the convoy (owner included) after the removal. */
  remainingMemberCount: number;
}

/**
 * Removes the CALLER from a convoy they have accepted.
 *
 * This gap exists because none of the shipped callables can serve it:
 * convoy.respond hard-requires inviteStatus === 'invited' and throws
 * failed-precondition once you have accepted; convoy.end is owner-only and ends
 * the drive for EVERYONE.
 *
 * The OWNER is refused (failed-precondition, not silently accepted): an owner
 * who left would orphan the convoy — nobody could then start it, end it, or
 * write the shared summary, because every lifecycle transition is owner-gated
 * and there is no succession rule. They must use convoy.end, which is the
 * honest action ("this is over for all of us") rather than a quiet exit that
 * strands the group. A DECLINED or still-INVITED member is also refused
 * (failed-precondition): there is nothing to leave, and convoy.respond is their
 * path.
 *
 * LAST NON-OWNER LEAVES → the convoy is left ALIVE with the owner alone, NOT
 * auto-ended. Ending is a deliberate act that writes the permanent summary
 * every member reads, and inferring it from the last person leaving would end
 * the owner's own drive out from under them — while they are quite possibly
 * still driving, and quite possibly about to invite someone else (convoy.invite
 * exists precisely so a convoy can regrow). The owner already has an End
 * button; the server should not press it for them. `remainingMemberCount` is
 * returned so the client can say "you're the only one left" without deriving it.
 */
export const leave = onCall(CALLABLE_OPTS, async (request): Promise<LeaveConvoyResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseConvoyIdInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const convoyId = parsed.input.convoyId;
  const ref = convoyRef(convoyId);

  interface LeaveOutcome {
    snapshot: Record<string, unknown>;
    remainingMemberCount: number;
  }
  // Assigned inside the transaction body (and re-assigned on a retry), so the
  // post-state is read from the transaction rather than from a fresh get().
  let result: LeaveOutcome | undefined;

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    // Not-found (never permission-denied) so a convoy can't be probed by a
    // non-member — matching respond/start/end.
    if (!snap.exists || !isConvoyMember(snap.data(), actor.uid)) {
      throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
    }
    const data = snap.data()!;
    if (data.status === 'ended') {
      throw new HttpsError('failed-precondition', CONVOY_ENDED_MESSAGE);
    }
    if (data.ownerUid === actor.uid) {
      throw new HttpsError('failed-precondition', OWNER_CANNOT_LEAVE_MESSAGE);
    }
    if (!isAcceptedConvoyMember(data, actor.uid)) {
      throw new HttpsError('failed-precondition', NOT_ACCEPTED_MEMBER_MESSAGE);
    }

    const update = buildLeaveConvoyUpdate(data, actor.uid);
    // memberUids / members / memberProfiles are written WHOLE in one update so
    // the three never disagree — every gate in this domain (rules read,
    // convoy chat, live positions) reads a different one of them.
    tx.update(ref, {
      memberUids: update.memberUids,
      members: update.members,
      memberProfiles: update.memberProfiles,
    });
    result = {
      snapshot: { ...data, ...update },
      remainingMemberCount: update.remainingAcceptedCount,
    };
  });

  // Read the post-transaction state from the transaction itself rather than
  // re-fetching: the caller is no longer in memberUids, so a fresh read would
  // be a read of a convoy they can no longer see. The summary is still built
  // with the caller's uid, which now yields viewer: null — correct, and the
  // point. (The transaction either assigned this or threw, so it is defined.)
  const settled = result!;
  return {
    convoy: toConvoySummary(convoyId, settled.snapshot, actor.uid, toIso),
    remainingMemberCount: settled.remainingMemberCount,
  };
});

// ---------------------------------------------------------------------------
// convoy.invite (grow an EXISTING convoy)
// ---------------------------------------------------------------------------

export interface InviteToConvoyResult {
  convoy: ConvoySummary;
  /** Uids actually invited (friend of the caller, unblocked, existing). */
  invited: string[];
  /** Requested uids that were skipped, with the reason. */
  skipped: SkippedInvitee[];
}

/**
 * Adds invitees to an existing convoy. convoy.create takes invitees only at
 * creation, so without this a convoy can never grow — the client's "+" button
 * had nothing to call.
 *
 * WHO MAY INVITE: any ACCEPTED member, not the owner alone.
 *
 * This is a small local car community, not a public network — every candidate
 * still has to be an established FRIEND of whoever invites them, so "any
 * member" cannot mean "any stranger". Within that, owner-only would be the
 * wrong shape: a convoy is a peer group, the owner is just whoever pressed
 * create, and the owner is frequently the one DRIVING — the least able to stop
 * and add the friend who just phoned to ask where everyone is. Restricting it
 * would make the common case (someone catching up with the group mid-drive)
 * impossible whenever the owner is at the wheel.
 *
 * The safeguards are the ones that actually matter: the friend edge is checked
 * against the INVITER, blocks are honoured against the inviter AND every
 * existing accepted member (so nobody can be pulled into a convoy with someone
 * who blocked them), the convoy is capped at MAX_CONVOY_SIZE, and every
 * addition is visible to the whole group in the roster they already read.
 * A still-INVITED or DECLINED member may not invite — they are not in the
 * convoy yet, or chose not to be.
 *
 * Notification: reuses the exact create path (writeInAppNotification under the
 * 'convoy_invite' category via notifyInvitees) rather than inventing a second
 * one, so an invitee's opt-out works identically however they were invited.
 */
export const invite = onCall(CALLABLE_OPTS, async (request): Promise<InviteToConvoyResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseInviteToConvoyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { convoyId, inviteeUids } = parsed.input;
  const ref = convoyRef(convoyId);

  // Pre-transaction read to gate + resolve invitees. The membership/status
  // checks are RE-ASSERTED inside the transaction below, which is what makes
  // the write safe; this read only decides whether the (expensive, multi-doc)
  // friend/block resolution is worth doing at all.
  const preSnap = await ref.get();
  if (!preSnap.exists || !isConvoyMember(preSnap.data(), actor.uid)) {
    throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
  }
  const preData = preSnap.data()!;
  if (preData.status === 'ended') {
    throw new HttpsError('failed-precondition', CONVOY_ENDED_MESSAGE);
  }
  if (!isAcceptedConvoyMember(preData, actor.uid)) {
    throw new HttpsError('failed-precondition', NOT_ACCEPTED_MEMBER_MESSAGE);
  }

  const existingMemberUids = Array.isArray(preData.memberUids)
    ? (preData.memberUids as string[])
    : [];
  if (existingMemberUids.length >= MAX_CONVOY_SIZE) {
    throw new HttpsError('failed-precondition', CONVOY_FULL_MESSAGE);
  }

  const inviterProfile = await loadProfile(actor.uid);
  const { invited, skipped } = await resolveInvitees(
    actor.uid,
    inviteeUids,
    existingMemberUids,
    // Block-check the candidate against every OTHER accepted member, not just
    // the inviter. Still-invited members are excluded: they have not joined, so
    // a block between them and a new invitee is not yet a shared room.
    acceptedMemberUids(preData).filter((uid) => uid !== actor.uid),
  );

  if (invited.length === 0) {
    throw new HttpsError('failed-precondition', NO_VALID_INVITEES_MESSAGE);
  }

  let addedUids: string[] = [];

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists || !isConvoyMember(snap.data(), actor.uid)) {
      throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
    }
    const data = snap.data()!;
    if (data.status === 'ended') {
      throw new HttpsError('failed-precondition', CONVOY_ENDED_MESSAGE);
    }
    if (!isAcceptedConvoyMember(data, actor.uid)) {
      throw new HttpsError('failed-precondition', NOT_ACCEPTED_MEMBER_MESSAGE);
    }

    // Re-read membership INSIDE the transaction: a concurrent invite may have
    // added some of these uids, or filled the convoy, since the pre-read.
    const currentMemberUids = Array.isArray(data.memberUids) ? (data.memberUids as string[]) : [];
    const currentSet = new Set(currentMemberUids);
    const stillNew = invited.filter((i) => !currentSet.has(i.uid));
    if (stillNew.length === 0) {
      throw new HttpsError('failed-precondition', NO_VALID_INVITEES_MESSAGE);
    }
    if (currentMemberUids.length + stillNew.length > MAX_CONVOY_SIZE) {
      // Refuse the whole batch rather than adding a prefix of it: silently
      // dropping the tail would tell the inviter some friends were added
      // without saying which, and a convoy is small enough that retrying with
      // fewer names is trivial.
      throw new HttpsError('failed-precondition', CONVOY_FULL_MESSAGE);
    }

    const memberPatch: Record<string, unknown> = {};
    const profilePatch: Record<string, unknown> = {};
    for (const invitee of stillNew) {
      memberPatch[invitee.uid] = buildMemberEntry(invitee.uid, 'member', 'invited', () =>
        FieldValue.serverTimestamp(),
        false,
      );
      profilePatch[invitee.uid] = {
        displayName: invitee.profile.displayName,
        avatarPath: invitee.profile.avatarPath,
      };
    }

    tx.set(
      ref,
      {
        // arrayUnion (not a computed array) so a concurrent invite/leave in
        // another transaction cannot be clobbered by a stale read.
        memberUids: FieldValue.arrayUnion(...stillNew.map((i) => i.uid)),
        members: memberPatch,
        memberProfiles: profilePatch,
      },
      { merge: true },
    );
    addedUids = stillNew.map((i) => i.uid);
  });

  const fresh = await ref.get();
  const freshData = fresh.data() ?? {};
  await notifyInvitees(
    addedUids,
    inviterProfile?.displayName ?? null,
    convoyId,
    typeof freshData.title === 'string' ? freshData.title : null,
  );

  return {
    convoy: toConvoySummary(convoyId, freshData, actor.uid, toIso),
    invited: addedUids,
    skipped,
  };
});

// ---------------------------------------------------------------------------
// convoy.setDestination / convoy.clearDestination (the SHARED destination)
// ---------------------------------------------------------------------------

/**
 * Sets (or REPLACES) the convoy's shared destination — the one place every
 * member can start navigation to.
 *
 * WHO MAY SET: any ACCEPTED member. A convoy is a peer group of people who are
 * already friends (create is friend-gated), and the person who knows where the
 * meet actually is is frequently not whoever pressed "create". Owner-only would
 * mean a convoy whose owner is driving — and therefore should not be typing an
 * address — cannot retarget at all. The safeguards are social rather than
 * hierarchical: the destination records WHO set it (server-stamped), so every
 * change is attributable in the bar, and the client confirms before overwriting
 * someone else's.
 *
 * SETTING REPLACES: last write wins, no queue, no multi-waypoint. A convoy has
 * exactly one "where we are all going"; stacking destinations would mean
 * members silently driving to different places. The whole object is written
 * atomically, so a half-updated destination is never observable.
 *
 * A `forming` convoy MAY have a destination — agreeing where to go is exactly
 * what happens before a convoy starts rolling. An `ended` one may not.
 *
 * ARRIVAL is deliberately NOT tracked and never auto-ends the convoy: "reached"
 * is unknowable server-side without a geofence over continuously-streamed
 * positions (materially bigger, more privacy-invasive and more battery-hungry
 * than a shared pin), and it would fire on the FIRST member to arrive while the
 * rest are still driving — ending other people's drive on a guess.
 *
 * DELIVERY CAVEAT (flagged, not silently accepted): the convoy read path is
 * POLLED today (convoy-list re-fetched after each mutation), not live. A
 * destination set by another member therefore appears on their next refresh,
 * not instantly. This callable writes the field the client already reads, so
 * promoting that read to a Firestore listener — the cheaper of the two options,
 * since the convoy doc is already member-readable by rules — is a follow-up
 * that needs no contract change.
 */
export const setDestination = onCall(
  CALLABLE_OPTS,
  async (request): Promise<{ convoy: ConvoySummary }> => {
    const actor = await requireMemberActor(request);

    const parsed = parseSetConvoyDestinationInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { convoyId, latitude, longitude, label } = parsed.input;
    const ref = convoyRef(convoyId);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      // Not-found (never permission-denied) so a convoy can't be probed.
      if (!snap.exists || !isConvoyMember(snap.data(), actor.uid)) {
        throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
      }
      const data = snap.data()!;
      if (data.status === 'ended') {
        throw new HttpsError('failed-precondition', CONVOY_ENDED_MESSAGE);
      }
      if (!isAcceptedConvoyMember(data, actor.uid)) {
        throw new HttpsError('failed-precondition', NOT_ACCEPTED_MEMBER_MESSAGE);
      }

      // setByUid comes from the verified auth context and setByDisplayName from
      // the roster the server already denormalized — never from the client. A
      // client-chosen setter uid would let someone attribute a destination to
      // another member.
      const profiles = (data.memberProfiles ?? {}) as Record<
        string,
        Record<string, unknown> | undefined
      >;
      const setByDisplayName = toProfileProjection(profiles[actor.uid]).displayName;

      tx.set(
        ref,
        {
          // The whole object at once — not field-by-field — so no reader ever
          // sees a new coordinate paired with the previous label.
          destination: {
            latitude,
            longitude,
            label: label ?? null,
            setByUid: actor.uid,
            setByDisplayName,
            setAt: Timestamp.fromDate(new Date()),
          },
        },
        { merge: true },
      );
    });

    const fresh = await ref.get();
    return { convoy: toConvoySummary(convoyId, fresh.data() ?? {}, actor.uid, toIso) };
  },
);

/**
 * Clears the convoy's shared destination.
 *
 * WHO MAY CLEAR: the member who SET it, or the convoy OWNER. Clearing is
 * strictly more destructive than replacing — it can leave members mid-drive
 * with nothing to navigate to, and unlike an overwrite it leaves no replacement
 * behind to explain itself. Setter-or-owner keeps the person who made the mess
 * able to undo it and gives the owner a moderation path, without letting any
 * member wipe a plan the group is following. Anyone else gets permission-denied
 * rather than not-found: they are a member, so the convoy's existence is
 * already known to them and not-found would simply mislead.
 *
 * Clearing when there is no destination is a NO-OP, not an error — two people
 * tapping at once is normal, and the post-state they wanted is the one they get.
 */
export const clearDestination = onCall(
  CALLABLE_OPTS,
  async (request): Promise<{ convoy: ConvoySummary }> => {
    const actor = await requireMemberActor(request);

    const parsed = parseConvoyIdInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const convoyId = parsed.input.convoyId;
    const ref = convoyRef(convoyId);

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists || !isConvoyMember(snap.data(), actor.uid)) {
        throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
      }
      const data = snap.data()!;
      if (data.status === 'ended') {
        // The destination on an ended convoy is a RECORD of where the group was
        // headed (it survives convoy.end untouched); there is nothing live left
        // to clear.
        throw new HttpsError('failed-precondition', CONVOY_ENDED_MESSAGE);
      }
      if (!isAcceptedConvoyMember(data, actor.uid)) {
        throw new HttpsError('failed-precondition', NOT_ACCEPTED_MEMBER_MESSAGE);
      }

      const current = toConvoyDestination(data.destination, toIso);
      if (!current) {
        // Idempotent no-op: already cleared.
        return;
      }
      const isOwner = data.ownerUid === actor.uid;
      if (!isOwner && current.setByUid !== actor.uid) {
        throw new HttpsError('permission-denied', DESTINATION_CLEAR_FORBIDDEN_MESSAGE);
      }

      tx.update(ref, { destination: FieldValue.delete() });
    });

    const fresh = await ref.get();
    return { convoy: toConvoySummary(convoyId, fresh.data() ?? {}, actor.uid, toIso) };
  },
);
