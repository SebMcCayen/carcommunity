package com.kungsbackacarcommunity.app.friends

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewer's relationship to one member, resolved from their OWN friend-list
 * snapshot. Pure logic — this is the input the member profile turns into a
 * control, so every branch that decides which button appears is pinned here.
 */
class FriendRelationshipTest {

    private fun friend(uid: String) = FriendSummary(uid, "Name-$uid", null, null)

    private fun request(
        otherUid: String,
        direction: FriendRequestDirection,
        requestId: String = "req-$otherUid",
    ) = FriendRequestSummary(
        requestId = requestId,
        fromUid = if (direction == FriendRequestDirection.Incoming) otherUid else "me",
        toUid = if (direction == FriendRequestDirection.Incoming) "me" else otherUid,
        direction = direction,
        otherUser = FriendUser(otherUid, "Name-$otherUid", null),
        createdAt = null,
    )

    private fun data(
        friends: List<FriendSummary> = emptyList(),
        incoming: List<FriendRequestSummary> = emptyList(),
        outgoing: List<FriendRequestSummary> = emptyList(),
    ) = FriendsData(friends, incoming, outgoing)

    @Test
    fun `a member absent from every list is not connected`() {
        assertEquals(
            FriendRelationship.None,
            resolveFriendRelationship(data(friends = listOf(friend("other"))), "target"),
        )
    }

    @Test
    fun `an established friendship resolves to Friends`() {
        assertEquals(
            FriendRelationship.Friends,
            resolveFriendRelationship(data(friends = listOf(friend("target"))), "target"),
        )
    }

    @Test
    fun `an outgoing pending request resolves to OutgoingPending`() {
        val snapshot =
            data(outgoing = listOf(request("target", FriendRequestDirection.Outgoing)))
        assertEquals(FriendRelationship.OutgoingPending, resolveFriendRelationship(snapshot, "target"))
    }

    @Test
    fun `an incoming pending request carries the request id needed to answer it`() {
        val snapshot =
            data(
                incoming =
                    listOf(request("target", FriendRequestDirection.Incoming, requestId = "r-42")),
            )
        assertEquals(
            FriendRelationship.IncomingPending("r-42"),
            resolveFriendRelationship(snapshot, "target"),
        )
    }

    @Test
    fun `friendship outranks a leftover pending request in either direction`() {
        // The mutual-send race leaves a pending request doc alongside a real
        // friendship (the backend befriends immediately). Reading that as
        // "pending" would offer to cancel or accept a request that no longer
        // decides anything, on a profile the viewer is already friends with.
        val snapshot =
            data(
                friends = listOf(friend("target")),
                incoming = listOf(request("target", FriendRequestDirection.Incoming)),
                outgoing = listOf(request("target", FriendRequestDirection.Outgoing)),
            )
        assertEquals(FriendRelationship.Friends, resolveFriendRelationship(snapshot, "target"))
    }

    @Test
    fun `an incoming request outranks a simultaneous outgoing one`() {
        // Same race, before either side is resolved: accepting settles the pair
        // in one tap, whereas cancelling the outgoing half would leave the
        // inbound request sitting unanswered.
        val snapshot =
            data(
                incoming =
                    listOf(request("target", FriendRequestDirection.Incoming, requestId = "in-1")),
                outgoing = listOf(request("target", FriendRequestDirection.Outgoing)),
            )
        assertEquals(
            FriendRelationship.IncomingPending("in-1"),
            resolveFriendRelationship(snapshot, "target"),
        )
    }

    @Test
    fun `a blank target uid never matches a row`() {
        // A malformed navigation argument must not resolve to some arbitrary
        // member's relationship.
        val snapshot =
            data(
                friends = listOf(FriendSummary("", null, null, null)),
                incoming = listOf(request("", FriendRequestDirection.Incoming)),
            )
        assertEquals(FriendRelationship.None, resolveFriendRelationship(snapshot, ""))
    }

    @Test
    fun `an empty snapshot is None, not Unknown`() {
        // Unknown means "we have not looked"; a successful empty read HAS
        // looked, and must offer Add friend rather than hide the control.
        assertEquals(FriendRelationship.None, resolveFriendRelationship(data(), "target"))
    }
}
