package com.kungsbackacarcommunity.app.friends

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure, client-side ordering of the established friends list. */
class FriendSortTest {

    private fun friend(
        uid: String,
        displayName: String?,
        friendsSince: String?,
    ) = FriendSummary(
        uid = uid,
        displayName = displayName,
        avatarPath = null,
        friendsSince = friendsSince,
    )

    private fun List<FriendSummary>.uids() = map { it.uid }

    @Test
    fun `NAME sorts case-insensitively by display name`() {
        val input = listOf(
            friend("c", "charlie", null),
            friend("a", "Alice", null),
            friend("b", "bob", null),
            friend("a2", "alice", null),
        )

        val sorted = sortFriends(input, FriendSort.NAME)

        // Alice/alice tie on a case-insensitive collator; the sort is stable so
        // the capitalised one (first in input) stays ahead.
        assertEquals(listOf("a", "a2", "b", "c"), sorted.uids())
    }

    @Test
    fun `NAME places Swedish a-ring a-diaeresis o-diaeresis after z`() {
        val input = listOf(
            friend("o", "Örjan", null),
            friend("a", "Åke", null),
            friend("z", "Zebra", null),
            friend("ae", "Ärla", null),
            friend("b", "Bertil", null),
        )

        val sorted = sortFriends(input, FriendSort.NAME)

        // Swedish collation orders ...z, å, ä, ö — the accented letters come
        // AFTER z, not interleaved with a/o as a ROOT-locale compare would do.
        assertEquals(listOf("b", "z", "a", "ae", "o"), sorted.uids())
    }

    @Test
    fun `NAME sorts rows with a missing name last`() {
        val input = listOf(
            friend("blank", "", "2026-01-01T00:00:00Z"),
            friend("named", "Bertil", "2026-01-01T00:00:00Z"),
            friend("null", null, "2026-01-01T00:00:00Z"),
        )

        val sorted = sortFriends(input, FriendSort.NAME)

        assertEquals("named", sorted.first().uid)
        assertEquals(setOf("blank", "null"), setOf(sorted[1].uid, sorted[2].uid))
    }

    @Test
    fun `RECENTLY_ADDED sorts newest friendsSince first`() {
        val input = listOf(
            friend("old", "Old", "2026-01-01T00:00:00Z"),
            friend("new", "New", "2026-07-01T00:00:00Z"),
            friend("mid", "Mid", "2026-04-01T00:00:00Z"),
        )

        val sorted = sortFriends(input, FriendSort.RECENTLY_ADDED)

        assertEquals(listOf("new", "mid", "old"), sorted.uids())
    }

    @Test
    fun `EARLIEST_ADDED sorts oldest friendsSince first`() {
        val input = listOf(
            friend("old", "Old", "2026-01-01T00:00:00Z"),
            friend("new", "New", "2026-07-01T00:00:00Z"),
            friend("mid", "Mid", "2026-04-01T00:00:00Z"),
        )

        val sorted = sortFriends(input, FriendSort.EARLIEST_ADDED)

        assertEquals(listOf("old", "mid", "new"), sorted.uids())
    }

    @Test
    fun `date sorts place a missing friendsSince last in both directions`() {
        val input = listOf(
            friend("null", "N", null),
            friend("dated", "D", "2026-04-01T00:00:00Z"),
            friend("blank", "B", ""),
        )

        // The only row with a timestamp leads both directions; the missing-date
        // rows fill the remaining places regardless of sort direction.
        assertEquals("dated", sortFriends(input, FriendSort.RECENTLY_ADDED).first().uid)
        assertEquals("dated", sortFriends(input, FriendSort.EARLIEST_ADDED).first().uid)
        assertEquals(
            setOf("null", "blank"),
            sortFriends(input, FriendSort.EARLIEST_ADDED).drop(1).map { it.uid }.toSet(),
        )
    }

    @Test
    fun `empty list stays empty for every sort`() {
        for (sort in FriendSort.entries) {
            assertEquals(emptyList<String>(), sortFriends(emptyList(), sort).uids())
        }
    }
}
