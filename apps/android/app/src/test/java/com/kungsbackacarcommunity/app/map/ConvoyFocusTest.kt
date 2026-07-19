package com.kungsbackacarcommunity.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The focus-mode state machine and the fit/refit decision. */
class ConvoyFocusTest {

    private val me = ConvoyLatLng(57.4874, 12.0757)
    private val other = ConvoyLatLng(57.5100, 12.1200)

    // ---- the plan ----------------------------------------------------------

    @Test
    fun `me mode always follows self`() {
        val plan = ConvoyFocusPlanner.plan(ConvoyFocusMode.Me, me, listOf(other))
        assertEquals(ConvoyCameraPlan.FollowSelf, plan)
    }

    @Test
    fun `convoy mode with known members fits them all`() {
        val plan = ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, me, listOf(other))
        val fit = plan as ConvoyCameraPlan.FitConvoy
        assertEquals(listOf(me, other), fit.points)
    }

    @Test
    fun `convoy mode with only my own position behaves like me mode`() {
        // The whole point: a convoy whose members have not started sharing yet
        // must not zoom out to the world for a one-point bounding box.
        val plan = ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, me, emptyList())
        assertEquals(ConvoyCameraPlan.FollowSelf, plan)
    }

    @Test
    fun `convoy mode with no positions at all behaves like me mode`() {
        val plan = ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, null, emptyList())
        assertEquals(ConvoyCameraPlan.FollowSelf, plan)
    }

    @Test
    fun `convoy mode with one member and no own position still has nothing to fit`() {
        val plan = ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, null, listOf(other))
        assertEquals(ConvoyCameraPlan.FollowSelf, plan)
    }

    @Test
    fun `convoy mode with two members and no own position fits both`() {
        val third = ConvoyLatLng(57.60, 12.30)
        val plan = ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, null, listOf(other, third))
        assertEquals(ConvoyCameraPlan.FitConvoy(listOf(other, third)), plan)
    }

    @Test
    fun `my own position is always part of the fit when known`() {
        val plan =
            ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, me, listOf(other))
                as ConvoyCameraPlan.FitConvoy
        assertTrue(plan.points.contains(me))
    }

    // ---- refit hysteresis --------------------------------------------------

    @Test
    fun `the first fit always happens`() {
        assertTrue(ConvoyFocusPlanner.shouldRefit(null, listOf(me, other)))
        assertTrue(ConvoyFocusPlanner.shouldRefit(emptyList(), listOf(me, other)))
    }

    @Test
    fun `gps jitter below the threshold does not move the camera`() {
        val before = listOf(me, other)
        val jittered = listOf(me, other.copy(latitude = other.latitude + 0.00005))
        assertFalse(ConvoyFocusPlanner.shouldRefit(before, jittered))
    }

    @Test
    fun `the group genuinely spreading out refits`() {
        val before = listOf(me, other)
        val spread = listOf(me, other.copy(latitude = other.latitude + 0.05))
        assertTrue(ConvoyFocusPlanner.shouldRefit(before, spread))
    }

    @Test
    fun `the group bunching back up refits`() {
        val before = listOf(me, ConvoyLatLng(57.90, 12.90))
        val bunched = listOf(me, ConvoyLatLng(57.49, 12.08))
        assertTrue(ConvoyFocusPlanner.shouldRefit(before, bunched))
    }

    @Test
    fun `a member moving inside the existing box does not refit`() {
        // Three members; the middle one moves, the bounding box does not.
        val corners = listOf(me, ConvoyLatLng(57.60, 12.30))
        val before = corners + ConvoyLatLng(57.52, 12.15)
        val after = corners + ConvoyLatLng(57.55, 12.20)
        assertFalse(ConvoyFocusPlanner.shouldRefit(before, after))
    }

    @Test
    fun `a member leaving the box refits`() {
        val before = listOf(me, ConvoyLatLng(57.60, 12.30))
        val after = before + ConvoyLatLng(57.90, 12.90)
        assertTrue(ConvoyFocusPlanner.shouldRefit(before, after))
    }

    @Test
    fun `an empty new set never refits`() {
        assertFalse(ConvoyFocusPlanner.shouldRefit(listOf(me, other), emptyList()))
    }

    @Test
    fun `bounds cover every point`() {
        val bounds = ConvoyFocusPlanner.boundsOf(listOf(me, other, ConvoyLatLng(57.0, 13.0)))
        assertEquals(57.0, bounds.south, 1e-9)
        assertEquals(57.51, bounds.north, 1e-9)
        assertEquals(12.0757, bounds.west, 1e-9)
        assertEquals(13.0, bounds.east, 1e-9)
    }

    // ---- the store ---------------------------------------------------------

    @Test
    fun `the store starts on me so the default path is untouched`() {
        assertEquals(ConvoyFocusMode.Me, ConvoyFocusStore().mode.value)
    }

    @Test
    fun `the store holds the choice for the session`() {
        val store = ConvoyFocusStore()
        store.onActiveConvoyChanged("convoy-1")
        store.setMode(ConvoyFocusMode.Convoy)
        assertEquals(ConvoyFocusMode.Convoy, store.mode.value)
    }

    @Test
    fun `leaving the convoy restores me`() {
        val store = ConvoyFocusStore()
        store.onActiveConvoyChanged("convoy-1")
        store.setMode(ConvoyFocusMode.Convoy)
        store.onActiveConvoyChanged(null)
        assertEquals(ConvoyFocusMode.Me, store.mode.value)
    }

    @Test
    fun `joining a different convoy does not inherit the previous choice`() {
        val store = ConvoyFocusStore()
        store.onActiveConvoyChanged("convoy-1")
        store.setMode(ConvoyFocusMode.Convoy)
        store.onActiveConvoyChanged("convoy-2")
        assertEquals(ConvoyFocusMode.Me, store.mode.value)
    }

    @Test
    fun `a repeated refresh of the same convoy does not clobber the choice`() {
        // The coordinator re-emits the same snapshot after every mutation, so
        // this is the common case, not an edge case.
        val store = ConvoyFocusStore()
        store.onActiveConvoyChanged("convoy-1")
        store.setMode(ConvoyFocusMode.Convoy)
        repeat(5) { store.onActiveConvoyChanged("convoy-1") }
        assertEquals(ConvoyFocusMode.Convoy, store.mode.value)
    }

    @Test
    fun `the camera plan goes back to follow-self the moment the convoy ends`() {
        // The end-to-end restore: mode reset plus plan reset, which is what
        // stops the camera being left stuck zoomed out over the whole group.
        val store = ConvoyFocusStore()
        store.onActiveConvoyChanged("convoy-1")
        store.setMode(ConvoyFocusMode.Convoy)
        assertTrue(
            ConvoyFocusPlanner.plan(store.mode.value, me, listOf(other))
                is ConvoyCameraPlan.FitConvoy,
        )

        store.onActiveConvoyChanged(null)
        assertEquals(
            ConvoyCameraPlan.FollowSelf,
            ConvoyFocusPlanner.plan(store.mode.value, me, listOf(other)),
        )
    }
}
