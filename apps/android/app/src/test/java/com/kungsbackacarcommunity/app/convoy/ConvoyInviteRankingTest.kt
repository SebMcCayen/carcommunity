package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.friends.FriendSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvoyInviteRankingTest {
    private fun friend(uid: String, name: String?) = FriendSummary(uid, name, null, null)

    @Test fun frequencyThenNormalizedNameThenUidIsIndependentOfSnapshotOrder() {
        val friends = listOf(friend("z", "Alice"), friend("b", "alice"), friend("n", null), friend("f", "Zoe"))
        val counts = mapOf("f" to 5, "b" to 2, "z" to 2, "removed" to 100)
        val expected = listOf("f", "b", "z", "n")
        assertEquals(expected, rankedConvoyFriends(friends, counts, "").map { it.uid })
        assertEquals(expected, rankedConvoyFriends(friends.reversed(), counts, "").map { it.uid })
        assertEquals(listOf("b", "z", "f", "n"), rankedConvoyFriends(friends, emptyMap(), "").map { it.uid })
    }

    @Test fun searchTrimsIgnoresCaseAndNormalizesSwedishUnicodeWithoutMatchingPrivateUid() {
        val friends = listOf(friend("private-id", "Åsa Andersson"), friend("b", "Bo"), friend("c", null))
        assertEquals(listOf("private-id"), rankedConvoyFriends(friends, emptyMap(), "  a\u030AsA  ").map { it.uid })
        assertEquals(listOf("private-id"), rankedConvoyFriends(friends, emptyMap(), "ANDERS").map { it.uid })
        assertEquals(emptyList<String>(), rankedConvoyFriends(friends, emptyMap(), "private-id").map { it.uid })
        assertEquals(3, rankedConvoyFriends(friends, emptyMap(), "  ").size)
    }

    @Test fun selectionIgnoresSearchButDropsRemovedFriendsAndSelf() {
        val friends = listOf(friend("me", "Me"), friend("a", "Alice"), friend("b", "Bo"))
        assertEquals(setOf("a", "b"), createConvoySelection(setOf("a", "b", "me", "removed"), friends, "me"))
        assertEquals(listOf("b"), rankedConvoyFriends(friends, emptyMap(), "bo").map { it.uid })
        assertEquals(setOf("a", "b"), createConvoySelection(setOf("a", "b"), friends, "me"))
    }
}
