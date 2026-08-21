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

    // ── remainingLifeFraction: drives the depleting bar under a trap glyph ──

    @Test
    fun `remainingLifeFraction is full at deploy and empty at expiry using the real span`() {
        val deployedAt = 1_000L
        val expiresAt = 1_000L + 6_000L // a 6 s span (deployedAt known)
        // Full at the deploy instant.
        assertEquals(
            1f,
            PerkMapVisuals.remainingLifeFraction(expiresAt, deployedAt, deployedAt),
            1e-4f,
        )
        // Half-way through the span.
        assertEquals(
            0.5f,
            PerkMapVisuals.remainingLifeFraction(expiresAt, deployedAt, 4_000L),
            1e-4f,
        )
        // Empty exactly at expiry.
        assertEquals(
            0f,
            PerkMapVisuals.remainingLifeFraction(expiresAt, deployedAt, expiresAt),
            1e-4f,
        )
    }

    @Test
    fun `remainingLifeFraction near-zero just before expiry`() {
        val deployedAt = 0L
        val expiresAt = 10_000L
        // 100 ms left of a 10 s span → a hair above 0, still positive (bar barely lit).
        val f = PerkMapVisuals.remainingLifeFraction(expiresAt, deployedAt, 9_900L)
        assertTrue(f > 0f && f < 0.02f)
    }

    @Test
    fun `remainingLifeFraction clamps to 0 when expired and to 1 on clock skew`() {
        val deployedAt = 0L
        val expiresAt = 10_000L
        // Past expiry → clamped to 0 (bar hidden).
        assertEquals(0f, PerkMapVisuals.remainingLifeFraction(expiresAt, deployedAt, 20_000L), 1e-4f)
        // `now` before the deploy (clock skew) → clamped to 1, never above.
        assertEquals(1f, PerkMapVisuals.remainingLifeFraction(expiresAt, deployedAt, -5_000L), 1e-4f)
    }

    @Test
    fun `remainingLifeFraction falls back to the TTL span when deployedAt is unknown`() {
        val fullMs = PerkMapVisuals.TRAP_FULL_LIFETIME_MS
        val now = 100_000L
        val expiresAt = now + fullMs / 2 // half the TTL remaining
        // No deployedAt → the span is the known TTL, so half the TTL left reads 0.5.
        assertEquals(0.5f, PerkMapVisuals.remainingLifeFraction(expiresAt, null, now), 1e-3f)
        // A non-positive derived span (createdAt >= expiresAt) also falls back to TTL.
        val badDeployedAt = expiresAt + 1_000L
        assertEquals(
            0.5f,
            PerkMapVisuals.remainingLifeFraction(expiresAt, badDeployedAt, now),
            1e-3f,
        )
    }

    // ── remainingClock: drives the popup's live "N min N s kvar" countdown ──

    @Test
    fun `remainingClock carves hours minutes seconds and rounds seconds up`() {
        val now = 0L
        // 2 min 30 s exactly.
        val a = PerkMapVisuals.remainingClock(150_000L, now)
        assertEquals(0, a.hours)
        assertEquals(2, a.minutes)
        assertEquals(30, a.seconds)
        assertFalse(a.isExpired)
        // 1 h 1 min 1 s.
        val b = PerkMapVisuals.remainingClock(3_661_000L, now)
        assertEquals(1, b.hours)
        assertEquals(1, b.minutes)
        assertEquals(1, b.seconds)
        // 1.5 s left rounds UP to 2 s (never shows 1 s early).
        val c = PerkMapVisuals.remainingClock(1_500L, now)
        assertEquals(2, c.seconds)
    }

    @Test
    fun `remainingClock is all-zero and expired at or past expiry`() {
        assertTrue(PerkMapVisuals.remainingClock(1_000L, 1_000L).isExpired)
        assertTrue(PerkMapVisuals.remainingClock(1_000L, 2_000L).isExpired)
        val clock = PerkMapVisuals.remainingClock(1_000L, 1_000L)
        assertEquals(0, clock.hours)
        assertEquals(0, clock.minutes)
        assertEquals(0, clock.seconds)
    }
}
