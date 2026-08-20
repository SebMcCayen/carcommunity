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

    @Test
    fun `staggeredAppearAlpha opens each glyph over its own slice of the sweep`() {
        val total = 4
        // At progress 0 nothing has opened yet.
        for (i in 0 until total) {
            assertEquals(0f, PerkMapVisuals.staggeredAppearAlpha(i, total, 0f), 1e-4f)
        }
        // At progress 1 every glyph is fully open.
        for (i in 0 until total) {
            assertEquals(1f, PerkMapVisuals.staggeredAppearAlpha(i, total, 1f), 1e-4f)
        }
        // The first glyph leads the last: partway through, earlier glyphs are more open.
        val mid = 0.5f
        assertTrue(
            PerkMapVisuals.staggeredAppearAlpha(0, total, mid) >
                PerkMapVisuals.staggeredAppearAlpha(total - 1, total, mid),
        )
        // Glyph 0 finishes opening at the end of its own slice (1/total).
        assertEquals(1f, PerkMapVisuals.staggeredAppearAlpha(0, total, 1f / total), 1e-4f)
    }

    @Test
    fun `staggeredAppearAlpha clamps out-of-range indices and progress`() {
        assertEquals(0f, PerkMapVisuals.staggeredAppearAlpha(0, 0, 0.5f), 1e-4f)
        assertEquals(0f, PerkMapVisuals.staggeredAppearAlpha(-1, 4, 0.5f), 1e-4f)
        assertEquals(0f, PerkMapVisuals.staggeredAppearAlpha(4, 4, 0.5f), 1e-4f)
        // Progress is clamped to 0..1.
        assertEquals(1f, PerkMapVisuals.staggeredAppearAlpha(0, 4, 5f), 1e-4f)
        assertEquals(0f, PerkMapVisuals.staggeredAppearAlpha(3, 4, -1f), 1e-4f)
    }
}
