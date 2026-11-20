package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.*
import org.junit.Test

class MapPresencePolicyTest {
    @Test fun `refresh and reorder do not restart entry or create an idle ticker`() {
        val policy = MapPresencePolicy()
        policy.sync(setOf("a", "b"), setOf("a", "b"), 0)
        assertEquals(0f, policy.frames(0).first().alpha)
        policy.sync(setOf("b", "a"), setOf("a", "b"), 100)
        assertEquals(.5f, policy.frames(100).first().alpha)
        assertEquals(1f, policy.frames(200).first().alpha)
        assertFalse(policy.isAnimating(200))
    }
    @Test fun `normal exit stops taps immediately and expires in two hundred milliseconds`() {
        val policy = MapPresencePolicy()
        policy.sync(setOf("a"), setOf("a"), 0)
        policy.sync(emptySet(), setOf("a"), 200)
        assertFalse(policy.frames(200).single().interactive)
        assertEquals(.5f, policy.frames(300).single().alpha)
        assertTrue(policy.frames(400).isEmpty())
        assertFalse(policy.isAnimating(400))
    }
    @Test fun `current authorization removes sensitive presentation without an exit frame`() {
        val policy = MapPresencePolicy()
        policy.sync(setOf("private"), setOf("private"), 0)
        policy.sync(emptySet(), emptySet(), 200)
        assertTrue(policy.frames(200).isEmpty())
        assertFalse(policy.isAnimating(200))
        policy.sync(setOf("private"), emptySet(), 300)
        assertTrue(policy.frames(300).isEmpty())
    }
    @Test fun `reentry reverses from current alpha without flash`() {
        val policy = MapPresencePolicy()
        policy.sync(setOf("a"), setOf("a"), 0)
        policy.sync(emptySet(), setOf("a"), 200)
        policy.sync(setOf("a"), setOf("a"), 300)
        assertEquals(.5f, policy.frames(300).single().alpha)
        assertEquals(1f, policy.frames(500).single().alpha)
    }
    @Test fun `slot changes hide placement jump and never introduce position interpolation`() {
        val policy = MapPresencePolicy()
        policy.sync(setOf("a"), setOf("a"), 0, placements = mapOf("a" to 1))
        policy.sync(setOf("a"), setOf("a"), 200, placements = mapOf("a" to 5))
        val frame = policy.frames(200).single()
        assertEquals(5, frame.placement)
        assertEquals(0f, frame.alpha)
        assertFalse(frame.interactive)
        assertEquals(1f, policy.frames(400).single().alpha)
    }
    @Test fun `reduced motion is immediate and clear discards every retained id`() {
        val policy = MapPresencePolicy()
        policy.sync(setOf("a"), setOf("a"), 0, animations = false)
        assertEquals(1f, policy.frames(0).single().alpha)
        assertFalse(policy.isAnimating(0))
        policy.sync(emptySet(), setOf("a"), 1, animations = false)
        assertTrue(policy.frames(1).isEmpty())
        policy.sync(setOf("a"), setOf("a"), 2)
        policy.clear()
        assertTrue(policy.frames(2).isEmpty())
    }
}
