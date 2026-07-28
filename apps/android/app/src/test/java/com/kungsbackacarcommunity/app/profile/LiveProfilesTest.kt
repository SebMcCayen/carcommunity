package com.kungsbackacarcommunity.app.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two hydration rules every surface depends on, isolated from any surface:
 * a live profile that EXISTS wins wholesale (including its nulls), and one that
 * is ABSENT falls back to the stored copy.
 */
class LiveProfilesTest {

    private val stored = LiveProfile(displayName = "Old Name", avatarPath = "profileImages/a/old.jpg")

    @Test
    fun `live profile replaces the stored copy`() {
        val live = mapOf("a" to LiveProfile("New Name", "profileImages/a/new.jpg"))

        assertEquals(LiveProfile("New Name", "profileImages/a/new.jpg"), LiveProfiles.resolve("a", stored, live))
    }

    @Test
    fun `live profile fills in an avatar the stored copy never had`() {
        val neverHadOne = LiveProfile(displayName = "Eva", avatarPath = null)
        val live = mapOf("a" to LiveProfile("Eva", "profileImages/a/first.jpg"))

        assertEquals("profileImages/a/first.jpg", LiveProfiles.resolve("a", neverHadOne, live).avatarPath)
    }

    @Test
    fun `a live null WINS over a stored avatar path`() {
        // A member who DELETES their avatar must stop showing it. Treating the
        // live null as "no opinion" would resurrect the deleted picture.
        val live = mapOf("a" to LiveProfile(displayName = "Old Name", avatarPath = null))

        assertEquals(null, LiveProfiles.resolve("a", stored, live).avatarPath)
    }

    @Test
    fun `an absent live profile falls back to the stored copy`() {
        // Absent means "nothing live to say about this member" (deleted account,
        // or a failed read) — NOT "this member has no avatar".
        assertEquals(stored, LiveProfiles.resolve("a", stored, live = emptyMap()))
    }

    @Test
    fun `one member never receives another member's profile`() {
        val live = mapOf("b" to LiveProfile("Someone Else", "profileImages/b/x.jpg"))

        assertEquals(stored, LiveProfiles.resolve("a", stored, live))
    }

    @Test
    fun `uidsOf de-duplicates repeated authors`() {
        // The load-bearing property for chat: a window of many messages from few
        // senders must cost a read per SENDER, not per message.
        val messages = listOf("a", "b", "a", "a", "b", "c")

        assertEquals(setOf("a", "b", "c"), LiveProfiles.uidsOf(messages) { it })
    }

    @Test
    fun `uidsOf drops blank and null uids`() {
        val messages = listOf("a", "", null, "  ")

        assertEquals(setOf("a"), LiveProfiles.uidsOf(messages) { it })
    }

    @Test
    fun `uidsOf of an empty list reads nothing`() {
        assertTrue(LiveProfiles.uidsOf(emptyList<String>()) { it }.isEmpty())
    }

    @Test
    fun `an empty live map leaves the stored identity untouched`() {
        // The config-less / failed-read path must be behaviour-identical to
        // before hydration existed.
        assertSame(stored, LiveProfiles.resolve("a", stored, emptyMap()))
    }
}
