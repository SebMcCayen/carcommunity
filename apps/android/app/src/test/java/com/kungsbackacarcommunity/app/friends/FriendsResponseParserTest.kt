package com.kungsbackacarcommunity.app.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing of the callable payloads (plain Map/List, as the SDK deserializes). */
class FriendsResponseParserTest {

    @Test
    fun `parseList maps friends and both request directions`() {
        val data =
            mapOf(
                "friends" to
                    listOf(
                        mapOf(
                            "uid" to "f1",
                            "displayName" to "Robin",
                            "avatarPath" to "avatars/f1",
                            "friendsSince" to "2026-07-01T10:00:00Z",
                        ),
                    ),
                "incoming" to
                    listOf(
                        mapOf(
                            "requestId" to "r1",
                            "fromUid" to "x",
                            "toUid" to "me",
                            "direction" to "incoming",
                            "otherUser" to mapOf("uid" to "x", "displayName" to "Kim", "avatarPath" to null),
                            "createdAt" to "2026-07-02T09:00:00Z",
                        ),
                    ),
                "outgoing" to
                    listOf(
                        mapOf(
                            "requestId" to "r2",
                            "fromUid" to "me",
                            "toUid" to "y",
                            "direction" to "outgoing",
                            "otherUser" to mapOf("uid" to "y", "displayName" to "Sam", "avatarPath" to "avatars/y"),
                            "createdAt" to null,
                        ),
                    ),
            )

        val parsed = FriendsResponseParser.parseList(data)

        assertEquals(1, parsed.friends.size)
        assertEquals("f1", parsed.friends[0].uid)
        assertEquals("Robin", parsed.friends[0].displayName)
        assertEquals(1, parsed.incoming.size)
        assertEquals(FriendRequestDirection.Incoming, parsed.incoming[0].direction)
        assertEquals("Kim", parsed.incoming[0].otherUser.displayName)
        assertEquals(1, parsed.outgoing.size)
        assertEquals(FriendRequestDirection.Outgoing, parsed.outgoing[0].direction)
        assertEquals("y", parsed.outgoing[0].otherUser.uid)
    }

    @Test
    fun `parseList drops rows missing required ids rather than crashing`() {
        val data =
            mapOf(
                "friends" to listOf(mapOf("displayName" to "No uid")),
                "incoming" to
                    listOf(
                        // Missing otherUser → dropped.
                        mapOf("requestId" to "r1", "direction" to "incoming"),
                    ),
                "outgoing" to "not a list",
            )
        val parsed = FriendsResponseParser.parseList(data)
        assertTrue(parsed.friends.isEmpty())
        assertTrue(parsed.incoming.isEmpty())
        assertTrue(parsed.outgoing.isEmpty())
    }

    @Test
    fun `parseList of null is empty`() {
        val parsed = FriendsResponseParser.parseList(null)
        assertTrue(parsed.friends.isEmpty() && parsed.incoming.isEmpty() && parsed.outgoing.isEmpty())
    }

    @Test
    fun `parseSendSuccess distinguishes requested from friends`() {
        assertEquals(SendRequestResult.NowFriends, FriendsResponseParser.parseSendSuccess(mapOf("status" to "friends")))
        assertEquals(SendRequestResult.Requested, FriendsResponseParser.parseSendSuccess(mapOf("status" to "requested")))
        // Missing status defaults to Requested (a 2xx is still a created request).
        assertEquals(SendRequestResult.Requested, FriendsResponseParser.parseSendSuccess(emptyMap()))
    }

    @Test
    fun `parseRespondSuccess distinguishes accepted from declined`() {
        assertEquals(RespondResult.Declined, FriendsResponseParser.parseRespondSuccess(mapOf("status" to "declined")))
        assertEquals(RespondResult.Accepted, FriendsResponseParser.parseRespondSuccess(mapOf("status" to "accepted")))
    }

    @Test
    fun `parseCandidates reads the ambiguity details`() {
        val details =
            mapOf(
                "reason" to "AMBIGUOUS_NICKNAME",
                "candidates" to
                    listOf(
                        mapOf("uid" to "a", "displayName" to "Alex", "avatarPath" to "avatars/a"),
                        mapOf("uid" to "b", "displayName" to "Alex", "avatarPath" to null),
                        mapOf("displayName" to "no uid — dropped"),
                    ),
            )
        val candidates = FriendsResponseParser.parseCandidates(details)
        assertEquals(2, candidates.size)
        assertEquals("a", candidates[0].uid)
        assertEquals("AMBIGUOUS_NICKNAME", FriendsResponseParser.reasonOf(details))
    }

    @Test
    fun `parseCandidates of non-map details is empty and reason is null`() {
        assertTrue(FriendsResponseParser.parseCandidates("oops").isEmpty())
        assertNull(FriendsResponseParser.reasonOf(null))
    }
}
