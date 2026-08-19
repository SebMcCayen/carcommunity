package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure client decisions for the Crown Hunt trap-trigger victim signal:
 * WHEN the per-victim drain listener is attached, and the once-per-attach replay
 * guard that stops a short-TTL event still present on (re)subscribe from popping
 * a fresh buzz. The SERVER is the sole authority for whether a drain happened;
 * these only guard the local UX (mirrors live.WavePresence).
 */
class PerkDrainPresenceTest {
    // --- listener gating ----------------------------------------------------

    @Test
    fun `listens only while perks enabled AND sharing`() {
        assertTrue(PerkDrainPresence.shouldListen(perksEnabled = true, isSharing = true))
    }

    @Test
    fun `does not listen while the perks flag is off, even when sharing`() {
        // Dark until crownHuntPerks flips: no listener attaches pre-launch.
        assertFalse(PerkDrainPresence.shouldListen(perksEnabled = false, isSharing = true))
    }

    @Test
    fun `does not listen when not sharing, even with perks enabled`() {
        // A drain can only fire on an accepted live position sample, which only
        // happens while sharing — so there is nothing to listen for otherwise.
        assertFalse(PerkDrainPresence.shouldListen(perksEnabled = true, isSharing = false))
        assertFalse(PerkDrainPresence.shouldListen(perksEnabled = false, isSharing = false))
    }

    // --- once-per-attach freshness guard ------------------------------------

    @Test
    fun `an event created after the subscribe instant is fresh`() {
        assertTrue(PerkDrainPresence.isFresh(createdAtMillis = 5_001, sinceMillis = 5_000))
    }

    @Test
    fun `an event at or before the subscribe instant is NOT replayed`() {
        // Strictly greater-than, mirroring the Firestore whereGreaterThan query —
        // a doc still inside its TTL on (re)attach must never re-pop.
        assertFalse(PerkDrainPresence.isFresh(createdAtMillis = 5_000, sinceMillis = 5_000))
        assertFalse(PerkDrainPresence.isFresh(createdAtMillis = 4_999, sinceMillis = 5_000))
    }
}
