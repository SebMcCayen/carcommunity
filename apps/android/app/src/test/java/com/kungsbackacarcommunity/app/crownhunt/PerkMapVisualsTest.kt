package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the perk map layer's draw decisions (PerkMapVisuals). */
class PerkMapVisualsTest {

    private fun trap(id: String, expiresAt: Long) =
        OwnTrapMarker(trapId = id, latitude = 57.0, longitude = 12.0, expiresAtMillis = expiresAt)

    @Test
    fun `liveTraps keeps only traps still in the future at now`() {
        val now = 1_000L
        val traps = listOf(trap("a", 2_000L), trap("b", 1_000L), trap("c", 500L))
        val live = PerkMapVisuals.liveTraps(traps, now)
        assertEquals(listOf("a"), live.map { it.trapId })
    }

    @Test
    fun `liveTraps of an empty list is empty`() {
        assertTrue(PerkMapVisuals.liveTraps(emptyList(), 1_000L).isEmpty())
    }

    @Test
    fun `isEffectActive is true only strictly before expiry`() {
        assertTrue(PerkMapVisuals.isEffectActive(1_001L, 1_000L))
        assertFalse(PerkMapVisuals.isEffectActive(1_000L, 1_000L))
        assertFalse(PerkMapVisuals.isEffectActive(999L, 1_000L))
        assertFalse(PerkMapVisuals.isEffectActive(null, 1_000L))
    }

    @Test
    fun `hasAnything is false when nothing is live`() {
        assertFalse(
            PerkMapVisuals.hasAnything(
                traps = listOf(trap("a", 500L)),
                shieldActiveUntilMillis = 900L,
                boostActiveUntilMillis = null,
                nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `hasAnything is true when a trap, a shield, or a boost is live`() {
        val now = 1_000L
        assertTrue(
            PerkMapVisuals.hasAnything(listOf(trap("a", 2_000L)), null, null, now),
        )
        assertTrue(PerkMapVisuals.hasAnything(emptyList(), 2_000L, null, now))
        assertTrue(PerkMapVisuals.hasAnything(emptyList(), null, 2_000L, now))
    }
}
