/**
 * convoy.create / convoy.respond / convoy.start / convoy.end / convoy.list —
 * member-gated convoy callables (contracts/functions/functions.json).
 *
 * Deployed via the `convoy` export group (functions/src/index.ts) as
 * `convoy-create`, `convoy-respond`, `convoy-start`, `convoy-end`,
 * `convoy-list`. This is the convoy FOUNDATION (sessions + invites + membership
 * + positions + summary); the 3-channel chat is a SEPARATE follow-up and is
 * intentionally NOT built here.
 *
 * Invariants:
 *  - Backend is the sole writer of convoys (firebase/firestore.rules grants
 *    member reads via memberUids, denies all client writes).
 *  - Only FRIENDS of the owner (users/{owner}/friends/{uid}) may be invited;
 *    blocking is honoured in BOTH directions at invite time. Non-friend /
 *    blocked / missing invitees are silently SKIPPED (reported in the response),
 *    never surfaced as an error that would reveal a block.
 *  - Membership + lifecycle transitions (forming → active → ended) run through
 *    these callables so the server owns them; the summary is computed + stored
 *    on convoy.end and is readable by ALL members.
 *  - LIVE POSITIONS reuse the live-location domain: the response carries
 *    livePositionUids (accepted members) and the client subscribes to RTDB
 *    liveLocation/{uid}/latest for each — the convoy never duplicates GPS
 *    storage (see convoy-core.ts).
 *  - On invite, a best-effort in-app notification is written for each invitee
 *    (reusing writeInAppNotification). It uses the existing 'system_notice'
 *    category — a dedicated 'convoy_invite' category + an 'open_convoy' action
 *    type are the clean follow-up (they require the product/security review the
 *    notifications domain mandates for new categories; see FINAL flag).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { isRestricted, toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';
import {
  CONVOY_ALREADY_ENDED_MESSAGE,
  CONVOY_ENDED_MESSAGE,
  CONVOY_NOT_FORMING_MESSAGE,
  CONVOY_NOT_FOUND_MESSAGE,
  INVITE_ALREADY_HANDLED_MESSAGE,
  NOT_INVITED_MESSAGE,
  NO_VALID_INVITEES_MESSAGE,
  MAX_CONVOYS_RETURNED,
  buildConvoyDocument,
  computeConvoySummary,
  isConvoyMember,
  memberEntry,
  parseConvoyIdInput,
  parseCreateConvoyInput,
  parseListConvoysInput,
  parseRespondConvoyInput,
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
 * or restricted — soft-deleted OR suspended (mirrors friends loadProfile) so a
 * restricted account is never invited/denormalized into a convoy.
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || isRestricted(toUserAccessState(snap.data()))) {
    return null;
  }
  return toProfileProjection(snap.data());
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

  // De-duplicate the requested list (preserving REQUEST order) and validate
  // each candidate: must be a friend of the owner, not blocked either way, and
  // a non-restricted existing account. Everything else is silently skipped.
  //
  // The per-invitee reads run concurrently (Promise.all over the input array,
  // index-aligned outcomes), but the invited/skipped output arrays are then
  // assembled sequentially in request order — so the response order is
  // deterministic and matches the request order, which clients rely on. The
  // self/duplicate classification runs in each task's synchronous prologue
  // (before the first await), so `seen` is populated in request order too.
  type InviteeOutcome =
    | { kind: 'invited'; uid: string; profile: ProfileProjection }
    | { kind: 'skipped'; skip: SkippedInvitee };

  const seen = new Set<string>();
  const outcomes = await Promise.all(
    inviteeUids.map(async (uid): Promise<InviteeOutcome> => {
      if (uid === actor.uid) {
        return { kind: 'skipped', skip: { uid, reason: 'self' } };
      }
      if (seen.has(uid)) {
        return { kind: 'skipped', skip: { uid, reason: 'duplicate' } };
      }
      seen.add(uid);

      const [friendSnap, ownerBlockedThem, theyBlockedOwner, profile] = await Promise.all([
        friendshipRef(actor.uid, uid).get(),
        blockRef(actor.uid, uid).get(),
        blockRef(uid, actor.uid).get(),
        loadProfile(uid),
      ]);

      if (ownerBlockedThem.exists || theyBlockedOwner.exists) {
        return { kind: 'skipped', skip: { uid, reason: 'blocked' } };
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

  // Assemble outputs in request order (index-aligned to inviteeUids).
  const invited: Array<{ uid: string; profile: ProfileProjection }> = [];
  const skipped: SkippedInvitee[] = [];
  for (const outcome of outcomes) {
    if (outcome.kind === 'invited') {
      invited.push({ uid: outcome.uid, profile: outcome.profile });
    } else {
      skipped.push(outcome.skip);
    }
  }

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

  // Best-effort in-app invite notifications (never fail the create). Reuses the
  // existing 'system_notice' category — a dedicated convoy_invite category is a
  // reviewed follow-up.
  const ownerName = ownerProfile.displayName ?? 'En vän';
  await Promise.all(
    invited.map((invitee) =>
      writeInAppNotification(invitee.uid, {
        category: 'system_notice',
        title: 'Konvoj-inbjudan',
        previewText: `${ownerName} har bjudit in dig till en konvoj${title ? `: ${title}` : ''}.`,
        actionType: 'open_notifications',
        relatedEntityId: ref.id,
      }).catch(() => undefined),
    ),
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
    tx.set(ref, { status: 'active', startedAt: FieldValue.serverTimestamp() }, { merge: true });
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
    const endedAt = new Date();
    const summary = computeConvoySummary(snap.data()!, endedAt, toDate);
    tx.set(
      ref,
      { status: 'ended', endedAt: Timestamp.fromDate(endedAt), summary },
      { merge: true },
    );
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
