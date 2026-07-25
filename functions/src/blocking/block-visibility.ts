/**
 * Block VISIBILITY core (pure logic) — the per-viewer "who must I never see"
 * set that makes a block MUTUALLY invisible across the chat surfaces.
 *
 * ## Why a mirror instead of querying userBlocks per read
 *
 * The authoritative graph is directional: `userBlocks/{blockerUid}/blocked/
 * {blockedUid}`, owner-readable only (firestore.rules) — deliberately, so the
 * blocked side can never enumerate who blocked them. Answering "is this uid
 * hidden from viewer V" therefore needs BOTH directions:
 *
 *   1. V blocked U   → readable by V (one subcollection).
 *   2. U blocked V   → lives in U's subcollection, which V cannot read at all,
 *                      and which the server can only reach with one point read
 *                      PER candidate.
 *
 * Direction 2 is what makes a per-read resolution expensive: a chat page of 30
 * messages from 30 distinct senders costs 30 point reads on every list call,
 * and the Android live listener (a direct Firestore snapshot listener, not a
 * callable) cannot resolve it at all.
 *
 * So `blocking-onBlockWrite` maintains a denormalized UNION at
 * `blockVisibility/{uid}.hiddenUids` — for every block edge A→B it adds B to
 * A's array and A to B's array (and removes them again when the last edge
 * between the pair goes away). That turns every read path into ONE document
 * read (server) or ONE snapshot listener (client) for the whole session,
 * regardless of how many messages or markers are being filtered.
 *
 * ## Privacy trade-off (deliberate, and NOT hidden)
 *
 * `hiddenUids` is a union of both directions, so a viewer who subtracts their
 * own `userBlocks` list is left with the uids that blocked THEM. That weakens
 * the "a block never reveals itself to the target" property of the blocking
 * domain. It is accepted because the product requirement is precisely that the
 * blocked party stops seeing the blocker — which is observable from the
 * disappearing messages anyway. The doc is owner-read-only (firestore.rules)
 * and carries uids only: no displayName, no direction, no timestamp, so it
 * never says WHO blocked whom, only that the pair is mutually hidden.
 *
 * ## Bound
 *
 * The array is capped at MAX_HIDDEN_UIDS entries per viewer (see the writer in
 * blockVisibilityStore.ts), so `blockVisibility/{uid}` — which the client holds
 * a live listener on — can never grow into a large document re-downloaded on
 * every change.
 *
 * WHAT THE CAP COSTS, precisely: past it a further block is no longer mirrored,
 * so EVERY surface reading this mirror stops hiding that pair — not only the
 * client-side live-window filters but the SERVER-side ones too
 * (communityChat.list, convoyChat.list, the convoyChat.post notification
 * fan-out, and dm.listConversations).
 *
 * What still enforces a block past the cap is every surface that resolves a
 * KNOWN pair directly against the authoritative `userBlocks` edges — which is
 * also where confidentiality actually matters:
 *   - the live map (live.listNearby's block matrix, plus the RTDB
 *     liveLocationBlocks rule on the marker stream),
 *   - dm.getMessages / dm.markRead / dm.sendMessage,
 *   - the firestore.rules gate on conversations/{pairId}/messages.
 *
 * So the cap degrades the CHANNEL surfaces — shared rooms every active member
 * can read anyway — and leaves the private ones fully enforced. At 1000 blocked
 * users it is not a bound a real account reaches.
 *
 * Firebase-free so it is unit-testable without the emulator.
 */

/** Top-level collection holding the per-viewer mirror. Owner-read, no client writes. */
export const BLOCK_VISIBILITY_COLLECTION = 'blockVisibility';

/** Array field on `blockVisibility/{uid}` holding the mutually-hidden uids. */
export const HIDDEN_UIDS_FIELD = 'hiddenUids';

/**
 * Upper bound on mirrored uids per viewer. 1000 uids ≈ 30 KB, comfortably under
 * Firestore's 1 MiB document limit while keeping the client listener cheap.
 * Real block counts are in the single/low double digits.
 */
export const MAX_HIDDEN_UIDS = 1000;

/**
 * Reads the stored `hiddenUids` array into a Set. A missing document, a missing
 * field, or a malformed entry yields an EMPTY set rather than throwing — a
 * viewer with no blocks is the overwhelmingly common case and must not cost a
 * failed read path.
 */
export function toHiddenUidSet(doc: Record<string, unknown> | undefined): Set<string> {
  const raw = doc?.[HIDDEN_UIDS_FIELD];
  if (!Array.isArray(raw)) return new Set<string>();
  const hidden = new Set<string>();
  for (const entry of raw) {
    if (typeof entry === 'string' && entry.length > 0) hidden.add(entry);
  }
  return hidden;
}

/** True when `uid` is mutually hidden from the viewer whose set this is. */
export function isHiddenUid(uid: string | null | undefined, hidden: ReadonlySet<string>): boolean {
  return typeof uid === 'string' && hidden.has(uid);
}

/**
 * Drops every item authored by a mutually-hidden uid.
 *
 * Filtering happens IN MEMORY against a set loaded once per request/session —
 * there is deliberately no per-item lookup here, so the cost of hiding a
 * blocked party is O(items) CPU and zero extra reads.
 *
 * An item whose author cannot be determined (`null`/`undefined`) is KEPT: a
 * malformed message is a rendering problem, not a block-evasion vector (the
 * only way to reach this state is a document written by the backend itself,
 * since none of these collections accept client writes).
 */
export function filterHiddenAuthors<T>(
  items: T[],
  authorUidOf: (item: T) => string | null | undefined,
  hidden: ReadonlySet<string>,
): T[] {
  // Most viewers have blocked nobody, so the empty set is the hot path: return
  // the SAME array rather than copying it. Nothing here or downstream mutates
  // the result, so sharing the instance is safe and saves an O(n) allocation on
  // every list call.
  if (hidden.size === 0) return items;
  return items.filter((item) => !isHiddenUid(authorUidOf(item), hidden));
}

/**
 * The uids to write for a pair after a block edge changes.
 *
 * `blockVisibility` is symmetric and edge-COUNTED, not edge-directed: the pair
 * stays mutually hidden while EITHER direction has a block, so an unblock only
 * clears the mirror when the opposite edge is gone too. Returning a plain
 * decision keeps that rule unit-testable away from Firestore.
 *
 * @param edgeExistsAfter  whether the edge that just changed exists now.
 * @param reverseEdgeExists whether the opposite direction is currently blocked.
 */
export function shouldHidePair(edgeExistsAfter: boolean, reverseEdgeExists: boolean): boolean {
  return edgeExistsAfter || reverseEdgeExists;
}
