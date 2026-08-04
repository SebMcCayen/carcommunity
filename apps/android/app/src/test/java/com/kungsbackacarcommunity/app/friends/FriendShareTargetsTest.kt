package com.kungsbackacarcommunity.app.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure share-eligibility + ordering transform. */
class FriendShareTargetsTest {
    private fun friend(uid: String, name: String?) =
        FriendSummary(uid = uid, displayName = name, avatarPath = null, friendsSince = null)

    @Test
    fun `established friends are returned name-ordered`() {
        val data =
            FriendsData(
                friends = listOf(friend("u1", "Öjvind"), friend("u2", "Anna"), friend("u3", "björn")),
                incoming = emptyList(),
                outgoing = emptyList(),
            )
        // Swedish collation: Anna, björn (case-insensitive), then Ö last.
        assertEquals(listOf("Anna", "björn", "Öjvind"), FriendShareTargets.from(data).map { it.displayName })
    }

    @Test
    fun `a blank-uid friend row is dropped`() {
        val data =
            FriendsData(
                friends = listOf(friend("", "Ghost"), friend("u1", "Anna")),
                incoming = emptyList(),
                outgoing = emptyList(),
            )
        val result = FriendShareTargets.from(data)
        assertEquals(listOf("u1"), result.map { it.uid })
    }

    @Test
    fun `pending requests are never share targets`() {
        val data =
            FriendsData(
                friends = emptyList(),
                incoming =
                    listOf(
                        FriendRequestSummary(
                            requestId = "r1",
                            fromUid = "u9",
                            toUid = "me",
                            direction = FriendRequestDirection.Incoming,
                            otherUser = FriendUser("u9", "Pending", null),
                            createdAt = null,
                        ),
                    ),
                outgoing = emptyList(),
            )
        assertTrue(FriendShareTargets.from(data).isEmpty())
    }
}
