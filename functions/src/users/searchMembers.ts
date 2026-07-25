/**
 * userSearch.members — live member typeahead (contracts/functions/functions.json).
 *
 * Deployed via the `userSearch` export group as `userSearch-members`
 * (europe-west1). Backs the "find a person" field on the Friends surface: the
 * client sends the partial text as it is typed (debounced) and renders the
 * returned rows, each of which opens that member's profile.
 *
 * MATCHING: case-insensitive PREFIX over the denormalized `displayNameLower`
 * key — 'gt' finds 'gt_86', '86' does not. The full contract, and why a
 * substring/n-gram index is deliberately not built, is in user-search-core.ts.
 *
 * WHY A CALLABLE AND NOT A CLIENT QUERY
 * ------------------------------------
 * firebase/firestore.rules grants `allow read: if isAuthenticated()` on
 * `users/{userId}`, so a client COULD run this range query itself — and that is
 * exactly the problem: rules cannot bound a query's result set, cannot require a
 * minimum prefix, cannot rate-limit, cannot strip fields, and cannot exclude
 * blocked users. A client-side query would hand every signed-in caller a
 * paginated dump of the entire member directory including `email` and every
 * backend-managed flag on the document. Running it here means the caller
 * receives an allowlisted three-field projection, at most
 * MAX_SEARCH_RESULTS rows, with no cursor to page past them.
 *
 * BOUNDS, all fixed server-side and none derived from client input:
 *  - minimum query length (MIN_QUERY_CODE_POINTS) — refuses a 1-char near-scan;
 *  - a single range scan capped at SEARCH_SCAN_LIMIT documents;
 *  - at most MAX_SEARCH_RESULTS returned, clamped (never honoured) from the
 *    optional `limit`;
 *  - a per-uid fixed-window rate limit, checked before the scan.
 *
 * BLOCKING: rows are filtered in BOTH directions (the caller blocked them, or
 * they blocked the caller), mirroring friend.sendRequest's either-way rule. The
 * filtered row is simply absent — there is no marker, no count, and no reason
 * code, so a search result can never reveal that a block exists or who set it.
 * This is scoped to THIS endpoint's rows only; general block-invisibility across
 * the app is a separate change.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { DocumentReference, DocumentSnapshot } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { isRestricted, toUserAccessState } from '../shared/access';
import {
  MEMBER_SEARCH_RATE_LIMIT_COLLECTION,
  QUERY_TOO_SHORT_MESSAGE,
  RATE_LIMITED_MESSAGE,
  REASON_QUERY_TOO_SHORT,
  SEARCH_SCAN_LIMIT,
  clampSearchLimit,
  isSearchableKey,
  isUnderMemberSearchRateLimit,
  memberSearchRateLimitDocId,
  memberSearchRateLimitExpiry,
  parseSearchMembersInput,
  searchKeyRange,
  toMemberSearchHit,
  toSearchQueryKey,
  type MemberSearchHit,
} from './user-search-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface SearchMembersResult {
  members: MemberSearchHit[];
}

function blockRef(blockerUid: string, blockedUid: string): DocumentReference {
  return db.collection('userBlocks').doc(blockerUid).collection('blocked').doc(blockedUid);
}

/**
 * Drops every candidate with a block edge to the caller in EITHER direction.
 *
 * One `getAll` batch-get for the whole page (2 refs per candidate) rather than a
 * per-candidate round trip, so the check costs one extra Firestore round trip
 * regardless of page size. It runs LAST — after the caller's own row and
 * restricted accounts are dropped and after the page is truncated to the
 * requested limit — so it is charged for at most `limit` candidates, not for the
 * whole raw scan.
 *
 * RESULTS ARE MATCHED BY DOCUMENT PATH, NEVER BY POSITION. Pairing candidate `i`
 * to `snapshots[i * 2]` would silently produce the wrong answer for every
 * candidate after any reordering or de-duplication of the batch-get, and the
 * dangerous direction of that failure is INCLUDING someone who blocked the
 * caller — a privacy leak rather than a visible bug. Keying off `snap.ref.path`
 * makes the check independent of the order the backend answered in, and matches
 * how the codebase's other batched identity join resolves its results
 * (events/listAttendees.ts loadProfiles keys by `snap.id`).
 *
 * A blocked row is removed WITHOUT backfilling another candidate from further
 * down the range: backfilling would make the result count depend on the block
 * graph in an observable way (search 'a', get 20; block someone, still get 20
 * but a different tail), and a typeahead losing one row is invisible. Absence is
 * the whole privacy property here.
 */
async function withoutBlockedEitherWay(
  callerUid: string,
  candidates: MemberSearchHit[],
): Promise<MemberSearchHit[]> {
  if (candidates.length === 0) {
    return candidates;
  }
  const pairs = candidates.map((candidate) => ({
    candidate,
    callerBlockedThem: blockRef(callerUid, candidate.uid),
    theyBlockedCaller: blockRef(candidate.uid, callerUid),
  }));
  const refs: DocumentReference[] = pairs.flatMap((pair) => [
    pair.callerBlockedThem,
    pair.theyBlockedCaller,
  ]);

  const snapshots: DocumentSnapshot[] = await db.getAll(...refs);
  const blockedPaths = new Set(snapshots.filter((snap) => snap.exists).map((snap) => snap.ref.path));

  return pairs
    .filter(
      (pair) =>
        !blockedPaths.has(pair.callerBlockedThem.path) &&
        !blockedPaths.has(pair.theyBlockedCaller.path),
    )
    .map((pair) => pair.candidate);
}

/**
 * Fixed-window per-user rate limit. Reads the deterministic counter doc for
 * (uid, current minute) BY ID — no query, no index — rejects with
 * `resource-exhausted` once the cap is reached, otherwise bumps it with a
 * contention-free `FieldValue.increment(1)` and stamps `expireAt` for the TTL
 * policy. A rejected call performs the single get and NO write.
 *
 * Under concurrency a few calls may read the same pre-increment count and slip
 * through at a window boundary. That is fine and deliberate: the goal is to stop
 * a runaway (thousands/min), not to be exact at the 90/91 boundary.
 */
async function enforceSearchRateLimit(uid: string): Promise<void> {
  const nowMs = Date.now();
  const ref = db
    .collection(MEMBER_SEARCH_RATE_LIMIT_COLLECTION)
    .doc(memberSearchRateLimitDocId(uid, nowMs));

  const snap = await ref.get();
  const currentCount = snap.get('count');
  if (!isUnderMemberSearchRateLimit(typeof currentCount === 'number' ? currentCount : 0)) {
    throw new HttpsError('resource-exhausted', RATE_LIMITED_MESSAGE);
  }

  await ref.set(
    {
      count: FieldValue.increment(1),
      uid,
      // Idempotent within a window: every write in the same minute sets the same
      // instant, so the merge is stable.
      expireAt: Timestamp.fromDate(memberSearchRateLimitExpiry(nowMs)),
    },
    { merge: true },
  );
}

export const searchMembers = onCall(CALLABLE_OPTS, async (request): Promise<SearchMembersResult> => {
  const actor = await requireMemberActor(request);

  // Validate BEFORE the rate limit so a malformed call is rejected without
  // touching Firestore at all — it neither pays for the counter get + write nor
  // burns the caller's window on a bad payload.
  const parsed = parseSearchMembersInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  const key = toSearchQueryKey(parsed.input.query);
  // Too short is checked before the rate limit for the same reason, and it is
  // the COMMON case on a typeahead (every first keystroke lands here). Tagged
  // with its own reason so the client can render "keep typing" silently instead
  // of treating it as the app-bug case that a malformed payload represents.
  if (!isSearchableKey(key)) {
    throw new HttpsError('invalid-argument', QUERY_TOO_SHORT_MESSAGE, {
      reason: REASON_QUERY_TOO_SHORT,
    });
  }

  await enforceSearchRateLimit(actor.uid);

  const limit = clampSearchLimit(parsed.input.limit);
  const { start, end } = searchKeyRange(key);

  // Single-field range + orderBy on the SAME field: covered by the automatic
  // single-field index, so this needs no composite index (identical shape to
  // friends/manageFriends.ts resolveTarget). Ordering by the key ASC also makes
  // the page deterministic and puts the shortest — i.e. closest — name first,
  // which is the one a typeahead should show at the top.
  const snapshot = await db
    .collection('users')
    .where('displayNameLower', '>=', start)
    .where('displayNameLower', '<', end)
    .orderBy('displayNameLower', 'asc')
    .limit(SEARCH_SCAN_LIMIT)
    .get();

  const candidates = snapshot.docs
    // Never surface the caller to themselves (they cannot friend themselves),
    // nor suspended/soft-deleted accounts — the same restriction friend
    // nickname resolution applies, so a name found here is a name that can
    // actually be acted on.
    .filter((doc) => doc.id !== actor.uid && !isRestricted(toUserAccessState(doc.data())))
    .slice(0, limit)
    .map((doc) => toMemberSearchHit(doc.id, doc.data()));

  return { members: await withoutBlockedEitherWay(actor.uid, candidates) };
});

// One-time deploy step for the rate-limit counter's TTL (spent windows
// self-delete so the collection never accumulates):
//
//   gcloud firestore fields ttls update expireAt \
//     --collection-group=memberSearchRateLimits --enable-ttl
//
// The collection is backend-only: written here via the Admin SDK and denied to
// all clients by firebase/firestore.rules. It needs no composite index (the
// counter is read by document id).
