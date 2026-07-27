package com.kungsbackacarcommunity.app.friends

/**
 * The viewer's relationship to ONE other member, as a screen (today: the member
 * profile) needs it in order to offer the right friend action.
 *
 * Derived from the caller's own `friend-list` snapshot — never from a read of
 * the other member's data. Under firebase/firestore.rules a user's
 * `users/{uid}/friends` subcollection is owner-only, so "are we friends?" is
 * answerable ONLY from the viewer's own side of the graph; `friend-list`
 * already returns exactly that (friends + both pending directions), which is
 * why this needs no new callable and no rules change.
 *
 * A BLOCK is deliberately not modelled here. A viewer-side block is decided a
 * layer up (the profile withholds itself entirely), and a block in the OTHER
 * direction is — by design — invisible to the client: the backend answers a
 * send attempt with the neutral NOT_ADDABLE either way, so inferring it here
 * would be exactly the leak that error is shaped to prevent.
 */
sealed interface FriendRelationship {
    /**
     * Not yet known — the snapshot hasn't loaded (or failed to). Distinct from
     * [None]: offering "Add friend" before we know would let the viewer send a
     * request to someone they are already friends with, and the callable would
     * answer with an avoidable error.
     */
    data object Unknown : FriendRelationship

    /** No friendship and no pending request either way. */
    data object None : FriendRelationship

    /**
     * The VIEWER has a pending request to this member, awaiting their reply.
     * Withdrawable via `friend-cancelRequest`, which addresses the request by
     * recipient — so, unlike [IncomingPending], no request id is needed here.
     */
    data object OutgoingPending : FriendRelationship

    /**
     * This member has a pending request to the VIEWER, awaiting their reply.
     * Carries the [requestId] because `friend-respondRequest` is addressed by
     * id.
     */
    data class IncomingPending(val requestId: String) : FriendRelationship

    /** An established friendship (the viewer's own side of it). */
    data object Friends : FriendRelationship
}

/**
 * Resolves the viewer's relationship to [targetUid] from their own
 * `friend-list` snapshot.
 *
 * PRECEDENCE — friends > incoming > outgoing — matters because the three lists
 * are not mutually exclusive in practice:
 *  - An established friendship WINS over any request row. Friendship (not
 *    request status) is the backend's source of truth for "already friends",
 *    and a request doc can legitimately still be pending alongside it under the
 *    mutual-send race (both parties send at once), where the backend befriends
 *    them immediately. Reading such a pair as "pending" would offer to cancel
 *    or accept a request that no longer decides anything.
 *  - An INCOMING request outranks an outgoing one for the same member — again
 *    the mutual-send race, and possible if a delete/resend interleaves.
 *    Accepting resolves the pair in one tap and befriends them (the backend
 *    auto-accepts the reverse), whereas cancelling the outgoing half would
 *    leave the inbound one sitting there unanswered.
 *
 * A blank [targetUid] never matches, so a malformed navigation argument
 * resolves to [FriendRelationship.None] rather than to some arbitrary row.
 */
fun resolveFriendRelationship(data: FriendsData, targetUid: String): FriendRelationship {
    if (targetUid.isBlank()) return FriendRelationship.None
    if (data.friends.any { it.uid == targetUid }) return FriendRelationship.Friends
    data.incoming.firstOrNull { it.otherUser.uid == targetUid }?.let {
        return FriendRelationship.IncomingPending(it.requestId)
    }
    if (data.outgoing.any { it.otherUser.uid == targetUid }) return FriendRelationship.OutgoingPending
    return FriendRelationship.None
}
