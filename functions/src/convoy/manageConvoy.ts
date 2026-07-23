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
import { logger } from 'firebase-functions';
import { FieldPath, FieldValue, Timestamp, type Transaction } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import { writeInAppNotification } from '../notifications/deliver';
import {
  isLiveShareEnabled,
  startConvoyAutoSession,
  stopConvoyAutoSession,
} from '../live/session';
import {
  ACTIVE_CONVOY_STATUSES,
  ALREADY_IN_CONVOY_MESSAGE,
  isActiveConvoyParticipant,
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
  isBlockedAgainstAnyPeer,
  isConvoyMember,
  memberEntry,
  parseConvoyIdInput,
  parseCreateConvoyInput,
  parseInviteToConvoyInput,
  parseListConvoysInput,
  parseRespondConvoyInput,
  parseSetConvoyDestinationInput,
  resolvePeerBlockPairs,
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

/**
 * ITEM 1 — one convoy at a time. Throws failed-precondition (ALREADY_IN_CONVOY)
 * when `uid` is already an ACTIVE PARTICIPANT (owner or accepted member — see
 * isActiveConvoyParticipant) of any non-ended convoy OTHER than `excludeConvoyId`.
 *
 * The check runs as a transaction READ (tx.get on the query) so it is done
 * inside the SAME transaction that writes the new membership, closing the race
 * where two simultaneous creates/accepts both read "not in a convoy" and both
 * commit: Firestore's serializable isolation aborts and retries the second
 * transaction once the first commits a convoy that enters this query's result
 * set, and on retry the query sees it and rejects. (Being merely INVITED to a
 * forming convoy does NOT count — only accepted participation does — so a normal
 * pending invite never blocks acting elsewhere.)
 *
 * The query filters to ACTIVE_CONVOY_STATUSES on the server, so it reads only
 * the caller's live convoys, not their whole ended history. Needs the composite
 * index [memberUids ARRAY_CONTAINS, status] (firebase/firestore.indexes.json).
 */
async function assertNotAlreadyInConvoy(
  tx: Transaction,
  uid: string,
  excludeConvoyId?: string,
): Promise<void> {
  const activeConvoys = db
    .collection('convoys')
    .where('memberUids', 'array-contains', uid)
    .where('status', 'in', [...ACTIVE_CONVOY_STATUSES]);
  const snap = await tx.get(activeConvoys);
  const alreadyIn = snap.docs.some(
    (doc) => doc.id !== excludeConvoyId && isActiveConvoyParticipant(doc.data(), uid),
  );
  if (alreadyIn) {
    throw new HttpsError('failed-precondition', ALREADY_IN_CONVOY_MESSAGE);
  }
}

/**
 * ITEM 2 lifecycle — best-effort fan-out of the convoy live-session producer
 * over a set of uids. Every call is independently caught so one failure (or an
 * absent live-share flag) can never fail the convoy mutation that triggered it,
 * and so a partial fan-out still starts/stops everyone it can.
 */
async function forEachAutoSession(
  uids: string[],
  op: (uid: string) => Promise<unknown>,
): Promise<void> {
  await Promise.all(
    uids.map((uid) =>
      op(uid).catch((error) => {
        // A swallowed best-effort op is logged (not silently dropped) so a
        // convoy that fails to auto-start/stop a live session is diagnosable.
        logger.warn('convoy auto-session op failed', { uid, error: String(error) });
      }),
    ),
  );
}

/**
 * ITEM 2 activation — the auto-session fan-out shared by convoy.create (the
 * owner, at create time) and convoy.start (owner + accepted invitees), so the
 * two never diverge: a convoy going live starts a convoy-tagged live session for
 * EVERY accepted member, making "everyone can see everyone" true from the moment
 * it is active. Late joiners (accept after this point) auto-start via
 * convoy.respond.
 *
 * The live-share feature flag is read ONCE and passed into the fan-out, not
 * re-read per member — a convoy of MAX_CONVOY_SIZE would otherwise cost one
 * config/featureFlags read per member. startConvoyAutoSession is best-effort and
 * leaves any member's pre-existing MANUAL session untouched.
 */
async function startConvoyAutoSessionsForAccepted(
  data: Record<string, unknown>,
  convoyId: string,
): Promise<void> {
  const liveEnabled = await isLiveShareEnabled();
  if (!liveEnabled) {
    return;
  }
  await forEachAutoSession(acceptedMemberUids(data), (uid) =>
    startConvoyAutoSession(uid, convoyId, true),
  );
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
 * The peer block matrix is resolved ONCE up front (resolvePeerBlockPairs) in
 * candidates + peers reads rather than per-invitee-per-peer point reads, which
 * would be 2·candidates·peers — 1200 reads at the shipped bounds. See the
 * cost note on resolvePeerBlockPairs.
 *
 * Per-invitee reads run concurrently (index-aligned outcomes), and the ORDERED
 * outcome list is returned so the caller can reclassify an entry (convoy.invite
 * does, for uids a concurrent invite already added) without losing the REQUEST
 * order clients rely on. The self/duplicate/already-member classification runs
 * in each task's synchronous prologue (before the first await), so `seen` is
 * populated in request order too.
 */
type InviteeOutcome =
  | { kind: 'invited'; uid: string; profile: ProfileProjection }
  | { kind: 'skipped'; skip: SkippedInvitee };

/** Uids this blocker has blocked, out of `blockedUids` (≤ BLOCK_LOOKUP_CHUNK_SIZE). */
async function queryBlockedSubset(blockerUid: string, blockedUids: string[]): Promise<string[]> {
  const snap = await db
    .collection('userBlocks')
    .doc(blockerUid)
    .collection('blocked')
    .where(FieldPath.documentId(), 'in', blockedUids)
    .get();
  return snap.docs.map((doc) => doc.id);
}

/** Splits an ordered outcome list into the two response arrays, in request order. */
function partitionInviteeOutcomes(outcomes: InviteeOutcome[]): {
  invited: Array<{ uid: string; profile: ProfileProjection }>;
  skipped: SkippedInvitee[];
} {
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

async function resolveInvitees(
  inviterUid: string,
  requestedUids: string[],
  existingMemberUids: string[],
  blockPeerUids: string[],
): Promise<InviteeOutcome[]> {
  const existing = new Set(existingMemberUids);
  const seen = new Set<string>();

  const peerBlockPairs = await resolvePeerBlockPairs(
    requestedUids,
    blockPeerUids,
    queryBlockedSubset,
  );

  return Promise.all(
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

      const [friendSnap, inviterBlockedThem, theyBlockedInviter, profile] = await Promise.all([
        friendshipRef(inviterUid, uid).get(),
        blockRef(inviterUid, uid).get(),
        blockRef(uid, inviterUid).get(),
        loadProfile(uid),
      ]);

      if (
        inviterBlockedThem.exists ||
        theyBlockedInviter.exists ||
        // Resolved up front for the whole batch, not per pair — see
        // resolvePeerBlockPairs. Same semantics: a block in EITHER direction
        // against ANY accepted peer drops the candidate.
        isBlockedAgainstAnyPeer(uid, blockPeerUids, peerBlockPairs)
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
  const { invited, skipped } = partitionInviteeOutcomes(
    await resolveInvitees(actor.uid, inviteeUids, [], []),
  );

  if (invited.length === 0) {
    throw new HttpsError('failed-precondition', NO_VALID_INVITEES_MESSAGE);
  }

  // Let Firestore generate the convoy id directly on the target collection.
  const ref = db.collection('convoys').doc();
  // ITEM 1: the create is done inside a transaction whose ONLY read is the
  // "am I already in a convoy" check, so the check and the membership write are
  // one atomic unit — two simultaneous creates by the same user cannot both land
  // (the second aborts + retries, sees the first convoy, and is rejected).
  await db.runTransaction(async (tx) => {
    await assertNotAlreadyInConvoy(tx, actor.uid);
    // Build the document INSIDE the transaction so its local-clock startedAt is
    // the actual write instant on EVERY attempt: a retry (contention/abort)
    // recomputes it rather than committing a timestamp captured before the first
    // attempt — which would set startedAt earlier than the real go-live moment
    // and skew convoy.end's duration. A convoy is born ACTIVE (buildConvoyDocument
    // sets status:'active' + this startedAt), so it goes live the instant it is
    // created — the owner's auto live-session starts below and invitees join an
    // already-rolling convoy; there is no separate owner "start" step (which, on
    // the map-first home with no Start control, would leave the convoy stuck
    // `forming` forever). startedAt uses the LOCAL clock (not serverTimestamp) so
    // it shares a time source with convoy.end's local-clock endedAt — see
    // buildConvoyDocument. (createdAt + member timestamps use serverTimestamp
    // sentinels, already retry-safe: the server stamps them at commit.)
    const document = buildConvoyDocument(
      { ownerUid: actor.uid, title: title ?? null, ownerProfile, invitees: invited },
      () => FieldValue.serverTimestamp(),
      Timestamp.fromDate(new Date()),
    );
    tx.set(ref, document);
  });

  // Best-effort in-app invite notifications (never fail the create) — the same
  // single path convoy.invite uses.
  await notifyInvitees(
    invited.map((i) => i.uid),
    ownerProfile.displayName,
    ref.id,
    title ?? null,
  );

  const fresh = await ref.get();
  const freshData = fresh.data() ?? {};

  // ITEM 2: the convoy is active from this first moment, so start the owner's
  // auto live-session right here — the SAME activation fan-out convoy.start runs
  // — rather than waiting for a separate Start tap. At create time the only
  // accepted member is the owner; invitees auto-start when they accept
  // (convoy.respond) into the now-active convoy.
  await startConvoyAutoSessionsForAccepted(freshData, ref.id);

  return {
    convoy: toConvoySummary(ref.id, freshData, actor.uid, toIso),
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
    // ITEM 1: accepting is JOINING a second convoy, so it is gated exactly like
    // create — but declining is always allowed (it commits to nothing). The
    // check reads inside this transaction, before the membership write, so a
    // concurrent accept into another convoy cannot both succeed.
    if (action === 'accept') {
      await assertNotAlreadyInConvoy(tx, actor.uid, convoyId);
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

  // ITEM 2: a LATE joiner — someone accepting into a convoy that is ALREADY
  // active — auto-starts their own live session on accept, so they become
  // visible to the convoy without tapping "share live". Members who accept while
  // the convoy is still `forming` are auto-started later, by convoy.start.
  if (action === 'accept' && fresh.data()?.status === 'active') {
    // Best-effort (a live-share hiccup must not fail the accept), but logged —
    // consistent with forEachAutoSession — so a missing auto-session is
    // diagnosable rather than silently swallowed.
    await startConvoyAutoSession(actor.uid, convoyId).catch((error) => {
      logger.warn('convoy auto-session op failed', {
        uid: actor.uid,
        convoyId,
        error: String(error),
      });
    });
  }

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

  // ITEM 2: activation auto-starts a live session for EVERY accepted member
  // (owner + accepted invitees) — the SAME fan-out convoy.create now runs on
  // create. LEGACY path: convoys are born active (convoy.create), so this
  // callable no longer fires in the normal flow; it is kept as a harmless
  // no-op-guarded transition for any convoy that is still `forming`.
  await startConvoyAutoSessionsForAccepted(fresh.data() ?? {}, parsed.input.convoyId);

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

  // ITEM 2 lifecycle: ending the convoy stops the live session it auto-started
  // for each member — but ONLY that session (stopConvoyAutoSession matches the
  // convoyAutoStarted flag + this convoyId), so a member's own MANUAL live
  // session keeps running. Members remain in the doc after end, so the accepted
  // set is still readable here.
  await forEachAutoSession(acceptedMemberUids(fresh.data() ?? {}), (uid) =>
    stopConvoyAutoSession(uid, parsed.input.convoyId),
  );

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
  // ITEM 2 lifecycle: leaving stops the live session the convoy auto-started for
  // this member (and only that — a manual session or one from another convoy is
  // left running). Mirror of convoy.end, scoped to the single leaver. Best-effort
  // but logged, so a partial teardown (member left broadcasting) is diagnosable.
  await stopConvoyAutoSession(actor.uid, convoyId).catch((error) => {
    logger.warn('convoy auto-session op failed', {
      uid: actor.uid,
      convoyId,
      error: String(error),
    });
  });

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
 *
 * IDEMPOTENT in the uid sense: inviting someone who is already in the convoy —
 * whether they were there before the call or a concurrent invite added them
 * mid-call — SUCCEEDS with `invited: []` and an `already_member` skip, because
 * the post-state the caller asked for is the post-state that exists. Only a
 * request in which nothing was addable AND nothing was already a member is a
 * failed-precondition; that caller really did ask for something that did not
 * happen. No new response field is needed: the count of `invited` and the
 * `already_member` reason already say which of the two it was.
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
  // The cap rejects GROWTH, so the early-out has to ask whether growth was
  // actually requested — otherwise a full convoy answers an idempotent
  // re-invite of an existing member with "convoy is full", contradicting the
  // idempotence documented above. This is a pure set test on the pre-read: no
  // extra reads, and it still short-circuits the expensive friend/block
  // resolution for the case the cap is there to stop. Real growth is refused
  // again INSIDE the transaction against a fresh read, which is the check that
  // actually enforces the cap.
  const preMemberSet = new Set(existingMemberUids);
  const growthRequested = inviteeUids.some(
    (uid) => uid !== actor.uid && !preMemberSet.has(uid),
  );
  if (growthRequested && existingMemberUids.length >= MAX_CONVOY_SIZE) {
    throw new HttpsError('failed-precondition', CONVOY_FULL_MESSAGE);
  }

  const inviterProfile = await loadProfile(actor.uid);
  const outcomes = await resolveInvitees(
    actor.uid,
    inviteeUids,
    existingMemberUids,
    // Block-check the candidate against every OTHER accepted member, not just
    // the inviter. Still-invited members are excluded: they have not joined, so
    // a block between them and a new invitee is not yet a shared room.
    acceptedMemberUids(preData).filter((uid) => uid !== actor.uid),
  );
  const { invited } = partitionInviteeOutcomes(outcomes);

  // IDEMPOTENCE, not "no valid invitees". Nothing to add is only an ERROR when
  // none of the requested uids is already in the convoy: then the caller asked
  // for something that genuinely did not happen (non-friends, strangers) and
  // deserves to hear so. If at least one was `already_member`, the state they
  // asked for is the state that exists — the honest answer is success with
  // `invited: []` and the reason in `skipped`, not a failure that a client has
  // to translate back into "actually, fine".
  const alreadyMember = outcomes.some(
    (o) => o.kind === 'skipped' && o.skip.reason === 'already_member',
  );
  if (invited.length === 0) {
    if (!alreadyMember) {
      throw new HttpsError('failed-precondition', NO_VALID_INVITEES_MESSAGE);
    }
    // Served from preSnap — the snapshot that PASSED the member / accepted /
    // not-ended gate above — rather than a second `ref.get()`. A re-fetch would
    // return state nobody re-authorized: between the gate and the fetch the
    // caller may have left on another device, or the owner may have ended the
    // convoy, and this branch has no transaction to catch it. It is also the
    // only self-consistent answer: `already_member` was decided from preData,
    // so the roster reported here is the roster that decision was made on.
    return {
      convoy: toConvoySummary(convoyId, preData, actor.uid, toIso),
      invited: [],
      skipped: partitionInviteeOutcomes(outcomes).skipped,
    };
  }

  let addedUids: string[] = [];
  // Uids a CONCURRENT invite added between the pre-read and this transaction.
  // Reclassified as `already_member` below rather than failing the call: the
  // desired state is already true, and the race is not the caller's business.
  const racedUids = new Set<string>();

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
    racedUids.clear();
    for (const invitee of invited) {
      if (currentSet.has(invitee.uid)) racedUids.add(invitee.uid);
    }
    if (stillNew.length === 0) {
      // Every requested invitee was added by a concurrent invite while this
      // call was resolving them. The post-state the caller asked for is the
      // post-state that exists, so this is an idempotent no-op: they come back
      // as `already_member` skips, not a failed-precondition. (The transaction
      // may be retried, hence racedUids being rebuilt from scratch each pass.)
      addedUids = [];
      return;
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

  // Reclassify the raced uids IN PLACE in the ordered outcome list rather than
  // appending them, so `skipped` stays in REQUEST order even in the race.
  const { skipped } = partitionInviteeOutcomes(
    racedUids.size === 0
      ? outcomes
      : outcomes.map((outcome) =>
          outcome.kind === 'invited' && racedUids.has(outcome.uid)
            ? { kind: 'skipped', skip: { uid: outcome.uid, reason: 'already_member' } }
            : outcome,
        ),
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

      // tx.update (NOT set-with-merge) because a merge deep-merges nested maps:
      // omitting `label` under a merge would leave the PREVIOUS pick's label
      // attached to the new coordinates — exactly the half-updated destination
      // this write is supposed to make unobservable. update() replaces the
      // whole `destination` map, so the object below is the object stored.
      tx.update(ref, {
        destination: {
          latitude,
          longitude,
          // Stored ABSENT when there is no label (including blank-after-trim),
          // as the schema and the field docs both say — not as null. Wire-side
          // this is unchanged: toConvoyDestination maps a missing label to
          // `label: null` either way.
          ...(label === undefined ? {} : { label }),
          setByUid: actor.uid,
          setByDisplayName,
          // Server clock, not the function instance's wall clock: a runtime
          // Date is skewed per instance and not monotonic across them, which
          // would make setAt useless for ordering two near-simultaneous picks.
          setAt: FieldValue.serverTimestamp(),
        },
      });
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
