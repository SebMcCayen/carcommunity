package com.kungsbackacarcommunity.app.crownhunt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crown marker animator's phase/timing arithmetic — driven against an
 * injected clock so the spawn/despawn sequences, the appear/disappear diff and
 * the batch staggering are PROVEN here rather than eyeballed on a device (the GL
 * surface these numbers feed cannot be JVM-tested).
 */
class CrownMarkerAnimatorTest {

    private fun animator() =
        CrownMarkerAnimator(
            spawnDurationMs = 600,
            despawnDurationMs = 400,
            staggerStepMs = 100,
            maxStaggerSteps = 4,
        )

    private fun List<CrownAnimationState>.byId(id: String): CrownAnimationState? =
        firstOrNull { it.id == id }

    // ── Appear ───────────────────────────────────────────────────────────────

    @Test
    fun `a new id spawns then settles`() {
        val a = animator()
        a.sync(setOf("c1"), nowMs = 0)

        val start = a.frame(0).byId("c1")
        assertNotNull(start)
        assertEquals(CrownAnimationPhase.SPAWNING, start!!.phase)

        // Mid-spawn: visible, scaling, still SPAWNING.
        val mid = a.frame(300).byId("c1")!!
        assertEquals(CrownAnimationPhase.SPAWNING, mid.phase)

        // Past the spawn duration: settled at natural size, upright, opaque.
        val settled = a.frame(600).byId("c1")!!
        assertEquals(CrownAnimationPhase.SETTLED, settled.phase)
        assertEquals(1f, settled.scale, 1e-4f)
        assertEquals(0f, settled.rotationDegrees, 1e-4f)
        assertEquals(1f, settled.contentAlpha, 1e-4f)
        assertEquals(0f, settled.shineAlpha, 1e-4f)
    }

    @Test
    fun `re-syncing the same id does not restart the spawn`() {
        val a = animator()
        a.sync(setOf("c1"), nowMs = 0)
        a.frame(600) // settle it
        a.sync(setOf("c1"), nowMs = 700) // same set again

        val state = a.frame(700).byId("c1")!!
        assertEquals(CrownAnimationPhase.SETTLED, state.phase)
    }

    @Test
    fun `the spawn shows the light before the crown is fully up`() {
        val a = animator()
        a.sync(setOf("c1"), nowMs = 0)
        // Early in the spawn the shine is present and the crown is not yet at
        // full size — the light shines at the spot first.
        val early = a.frame(60).byId("c1")!!
        assertTrue("shine should be lit early", early.shineAlpha > 0f)
        assertTrue("crown not yet at full size", early.scale < 1f)
    }

    // ── Staggering ─────────────────────────────────────────────────────────────

    @Test
    fun `a batch of new crowns is staggered so they do not all start together`() {
        val a = animator()
        a.sync(setOf("b", "a", "c"), nowMs = 0)

        // At t=0 only the first (id-sorted) crown has started; the others are
        // still waiting out their stagger offset and so are not drawn yet.
        val atZero = a.frame(0)
        assertNotNull(atZero.byId("a"))
        assertNull(atZero.byId("b"))
        assertNull(atZero.byId("c"))

        // "b" appears at +100ms, "c" at +200ms (100ms stagger step).
        assertNotNull(a.frame(100).byId("b"))
        assertNull(a.frame(100).byId("c"))
        assertNotNull(a.frame(200).byId("c"))
    }

    @Test
    fun `the stagger is capped so a big batch does not trail off for minutes`() {
        val a = animator() // maxStaggerSteps = 4, step = 100ms
        val ids = (1..10).map { "id%02d".format(it) }.toSet()
        a.sync(ids, nowMs = 0)
        // Every crown has started by the cap (4 * 100ms), none left pending.
        val drawn = a.frame(400).map { it.id }.toSet()
        assertEquals(ids, drawn)
    }

    // ── Disappear ──────────────────────────────────────────────────────────────

    @Test
    fun `a removed id despawns and is kept rendered until the animation finishes`() {
        val a = animator()
        a.sync(setOf("c1"), nowMs = 0)
        a.frame(600) // settle
        a.sync(emptySet(), nowMs = 1000) // removed

        val mid = a.frame(1200).byId("c1")
        assertNotNull("despawning crown is still drawn", mid)
        assertEquals(CrownAnimationPhase.DESPAWNING, mid!!.phase)
        assertTrue("shrinking", mid.scale < 1f)
        assertTrue("spinning out", mid.rotationDegrees > 0f)

        // After the despawn duration it is gone from the draw set.
        assertNull(a.frame(1400).byId("c1"))
    }

    @Test
    fun `a crown that reappears while despawning starts a fresh spawn`() {
        val a = animator()
        a.sync(setOf("c1"), nowMs = 0)
        a.frame(600)
        a.sync(emptySet(), nowMs = 1000) // start despawn
        a.frame(1100)
        a.sync(setOf("c1"), nowMs = 1150) // it came back

        val state = a.frame(1150).byId("c1")!!
        assertEquals(CrownAnimationPhase.SPAWNING, state.phase)
    }

    // ── isAnimating drives the frame loop ───────────────────────────────────────

    @Test
    fun `isAnimating is true during a spawn and false once everything is settled`() {
        val a = animator()
        a.sync(setOf("c1"), nowMs = 0)
        assertTrue(a.isAnimating(0))
        assertTrue(a.isAnimating(300))
        assertFalse("settled crowns need no ticking", a.isAnimating(600))
    }

    @Test
    fun `isAnimating stays true through a pending staggered start`() {
        val a = animator()
        a.sync(setOf("a", "b"), nowMs = 0)
        // "b" has not started yet at t=0 but the loop must keep running so it can.
        assertTrue(a.isAnimating(0))
    }

    @Test
    fun `clear forgets everything`() {
        val a = animator()
        a.sync(setOf("c1", "c2"), nowMs = 0)
        a.clear()
        assertTrue(a.frame(0).isEmpty())
        assertFalse(a.isAnimating(0))
    }

    // ── Easing helpers ──────────────────────────────────────────────────────────

    @Test
    fun `easings hit their endpoints and easeOutBack overshoots`() {
        assertEquals(0f, CrownMarkerAnimator.easeOutBack(0f), 1e-4f)
        assertEquals(1f, CrownMarkerAnimator.easeOutBack(1f), 1e-4f)
        // The overshoot is the whole point of the pop: somewhere in the middle it
        // goes past 1 before settling back.
        val peak = (1..9).map { CrownMarkerAnimator.easeOutBack(it / 10f) }.max()
        assertTrue("easeOutBack should overshoot past 1", peak > 1f)

        assertEquals(0f, CrownMarkerAnimator.easeOutCubic(0f), 1e-4f)
        assertEquals(1f, CrownMarkerAnimator.easeOutCubic(1f), 1e-4f)
        assertEquals(0f, CrownMarkerAnimator.easeInCubic(0f), 1e-4f)
        assertEquals(1f, CrownMarkerAnimator.easeInCubic(1f), 1e-4f)
    }
}
