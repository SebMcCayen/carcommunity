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

    // ---- the flat-fit computation (the "don't see everyone" bug) -----------

    @Test
    fun `a convoy fit is computed dead flat so a tilted 3D view cannot over-zoom`() {
        // The bug: feeding the map's live ~45 degree tilt into the SDK fit made
        // it over-estimate coverage and return a too-tight zoom, dropping the
        // members nearest the screen edges. The fit must always be computed flat.
        assertEquals(0.0, ConvoyFocusPlanner.fitComputationContext(0.0).pitchDegrees, 1e-9)
        assertEquals(0.0, ConvoyFocusPlanner.fitComputationContext(45.0).pitchDegrees, 1e-9)
        assertEquals(0.0, ConvoyFocusPlanner.fitComputationContext(200.0).pitchDegrees, 1e-9)
    }

    @Test
    fun `a convoy fit keeps the current bearing so course-up framing survives`() {
        // Bearing is safe to feed into the fit and must be preserved so the fit
        // rotates with the direction of travel; only the pitch is flattened.
        assertEquals(0.0, ConvoyFocusPlanner.fitComputationContext(0.0).bearingDegrees, 1e-9)
        assertEquals(137.5, ConvoyFocusPlanner.fitComputationContext(137.5).bearingDegrees, 1e-9)
    }

    @Test
    fun `the framed set's bounds contain every member spread out across the map`() {
        // The points the camera is asked to frame (self + all known members)
        // must genuinely enclose everyone — a bounding box that contains the
        // whole group is the precondition for the camera fit to show them all.
        val self = ConvoyLatLng(57.00, 12.00)
        val a = ConvoyLatLng(57.50, 12.80)
        val b = ConvoyLatLng(56.70, 11.40)
        val fit =
            ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, self, listOf(a, b))
                as ConvoyCameraPlan.FitConvoy
        val bounds = ConvoyFocusPlanner.boundsOf(fit.points)
        for (p in listOf(self, a, b)) {
            assertTrue("latitude framed", p.latitude in bounds.south..bounds.north)
            assertTrue("longitude framed", p.longitude in bounds.west..bounds.east)
        }
    }

    @Test
    fun `a single-point set yields degenerate but valid bounds and does not crash`() {
        val only = ConvoyLatLng(57.0, 12.0)
        val bounds = ConvoyFocusPlanner.boundsOf(listOf(only))
        assertEquals(only.latitude, bounds.south, 1e-9)
        assertEquals(only.latitude, bounds.north, 1e-9)
        assertEquals(only.longitude, bounds.west, 1e-9)
        assertEquals(only.longitude, bounds.east, 1e-9)
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

    // ---- refit throttle (time gate on top of the distance gate) -------------

    private val spreadOut = listOf(me, other.copy(latitude = other.latitude + 0.05))

    @Test
    fun `the very first fit of a session is never time-suppressed`() {
        // lastFitAtMillis null == no fit yet; the button press must reframe now.
        assertTrue(
            ConvoyFocusPlanner.shouldRefitNow(
                previous = null,
                next = listOf(me, other),
                lastFitAtMillis = null,
                nowMillis = 0L,
            ),
        )
    }

    @Test
    fun `a material move within the interval is throttled out`() {
        // The box moved plenty, but only half a second has passed since the last
        // fit: too soon, so the camera holds.
        assertFalse(
            ConvoyFocusPlanner.shouldRefitNow(
                previous = listOf(me, other),
                next = spreadOut,
                lastFitAtMillis = 1_000L,
                nowMillis = 1_500L,
            ),
        )
    }

    @Test
    fun `a burst of material moves within the interval yields exactly one fit`() {
        // Simulate five position ticks 300 ms apart, all materially different,
        // feeding the applied-fit forward only when a fit is allowed — the way the
        // surface advances lastConvoyFitAtMillis only on a real ease. Just the
        // first should fit; the rest fall inside the 1.5 s window.
        var applied: List<ConvoyLatLng>? = listOf(me, other)
        var lastFitAt: Long? = 0L
        var fits = 0
        for (i in 1..5) {
            val now = i * 300L
            val next = listOf(me, other.copy(latitude = other.latitude + 0.05 * i))
            if (ConvoyFocusPlanner.shouldRefitNow(applied, next, lastFitAt, now)) {
                fits++
                applied = next
                lastFitAt = now
            }
        }
        assertEquals(1, fits)
    }

    @Test
    fun `a material move refits once the interval has elapsed`() {
        assertTrue(
            ConvoyFocusPlanner.shouldRefitNow(
                previous = listOf(me, other),
                next = spreadOut,
                lastFitAtMillis = 1_000L,
                nowMillis = 1_000L + ConvoyFocusPlanner.MIN_REFIT_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `jitter is still ignored even after the interval has elapsed`() {
        // The time gate opening does not license a fit the distance gate rejects:
        // a parked convoy sitting still must never drift the camera.
        val jittered = listOf(me, other.copy(latitude = other.latitude + 0.00005))
        assertFalse(
            ConvoyFocusPlanner.shouldRefitNow(
                previous = listOf(me, other),
                next = jittered,
                lastFitAtMillis = 0L,
                nowMillis = 10 * ConvoyFocusPlanner.MIN_REFIT_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `an empty roster never refits regardless of elapsed time`() {
        assertFalse(
            ConvoyFocusPlanner.shouldRefitNow(
                previous = listOf(me, other),
                next = emptyList(),
                lastFitAtMillis = 0L,
                nowMillis = Long.MAX_VALUE,
            ),
        )
    }

    // ---- zoom clamp (never building level, never a whole-country view) ------

    @Test
    fun `a zoom inside the band is left alone`() {
        assertEquals(12.0, ConvoyFocusPlanner.clampFitZoom(12.0, 8.0, 16.5, 14.0), 1e-9)
    }

    @Test
    fun `a bunched-up group is capped so it never frames at building level`() {
        assertEquals(16.5, ConvoyFocusPlanner.clampFitZoom(19.0, 8.0, 16.5, 14.0), 1e-9)
    }

    @Test
    fun `a spread-out group is floored so it never frames as a country map`() {
        assertEquals(8.0, ConvoyFocusPlanner.clampFitZoom(3.0, 8.0, 16.5, 14.0), 1e-9)
    }

    @Test
    fun `a missing or non-finite fit zoom falls back to the browsing zoom`() {
        // The SDK declines to give a zoom (null) or hands back a degenerate one
        // for a single-point cluster; both resolve to the fallback, then clamp.
        assertEquals(14.0, ConvoyFocusPlanner.clampFitZoom(null, 8.0, 16.5, 14.0), 1e-9)
        assertEquals(14.0, ConvoyFocusPlanner.clampFitZoom(Double.NaN, 8.0, 16.5, 14.0), 1e-9)
        assertEquals(
            16.5,
            ConvoyFocusPlanner.clampFitZoom(Double.POSITIVE_INFINITY, 8.0, 16.5, 20.0),
            1e-9,
        )
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

    // ---- focus-on-join (the accept-an-invite hand-off) ----------------------

    /**
     * The ordering the request exists for. Accepting an invite lands the member
     * on the map, but the convoy they joined is not the ACTIVE convoy until the
     * bar's coordinator refreshes — and that refresh is what resets the mode.
     * A plain `setMode` before it would therefore be undone by it.
     */
    @Test
    fun `a requested join focus survives the reset the arriving convoy triggers`() {
        val store = ConvoyFocusStore()
        store.requestConvoyFocusOnJoin("convoy-1")
        assertEquals("nothing to frame yet", ConvoyFocusMode.Me, store.mode.value)

        store.onActiveConvoyChanged("convoy-1")
        assertEquals(ConvoyFocusMode.Convoy, store.mode.value)
    }

    @Test
    fun `a join focus requested after the bar already knows the convoy is honoured now`() {
        // The other side of the same race: the refresh won.
        val store = ConvoyFocusStore()
        store.onActiveConvoyChanged("convoy-1")
        store.requestConvoyFocusOnJoin("convoy-1")
        assertEquals(ConvoyFocusMode.Convoy, store.mode.value)
    }

    @Test
    fun `the join focus is one-shot and does not re-arm on a later rejoin`() {
        val store = ConvoyFocusStore()
        store.requestConvoyFocusOnJoin("convoy-1")
        store.onActiveConvoyChanged("convoy-1")
        store.setMode(ConvoyFocusMode.Me)
        // Left and rejoined the same convoy later, with no new request.
        store.onActiveConvoyChanged(null)
        store.onActiveConvoyChanged("convoy-1")
        assertEquals(ConvoyFocusMode.Me, store.mode.value)
    }

    @Test
    fun `a join focus request never attaches itself to a different convoy`() {
        // The accept did not put the caller in convoy-1 after all; whatever they
        // join next is a different trip and keeps the plain default.
        val store = ConvoyFocusStore()
        store.requestConvoyFocusOnJoin("convoy-1")
        store.onActiveConvoyChanged("convoy-2")
        assertEquals(ConvoyFocusMode.Me, store.mode.value)
        // And the stale request is dropped rather than lying in wait.
        store.onActiveConvoyChanged("convoy-1")
        assertEquals(ConvoyFocusMode.Me, store.mode.value)
    }

    @Test
    fun `a blank join focus request is simply no request`() {
        val store = ConvoyFocusStore()
        store.requestConvoyFocusOnJoin("  ")
        store.onActiveConvoyChanged("convoy-1")
        assertEquals(ConvoyFocusMode.Me, store.mode.value)
    }

    /**
     * The degenerate join: you accepted, you are on the map, and nobody else in
     * the convoy is sharing a position yet. Focus is ON, but the camera must not
     * be handed a one-point (or empty) bounding box to fit.
     */
    @Test
    fun `focusing a convoy nobody has shared a position in yet still follows me`() {
        val store = ConvoyFocusStore()
        store.requestConvoyFocusOnJoin("convoy-1")
        store.onActiveConvoyChanged("convoy-1")

        assertEquals(
            ConvoyCameraPlan.FollowSelf,
            ConvoyFocusPlanner.plan(store.mode.value, me, emptyList()),
        )
        assertEquals(
            ConvoyCameraPlan.FollowSelf,
            ConvoyFocusPlanner.plan(store.mode.value, null, emptyList()),
        )
        // …and it becomes a real fit by itself the moment somebody does share.
        assertTrue(
            ConvoyFocusPlanner.plan(store.mode.value, me, listOf(other))
                is ConvoyCameraPlan.FitConvoy,
        )
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

    /**
     * The distinction the surface's `toggled` check depends on.
     *
     * `plan` returns FollowSelf for TWO different reasons: the user switched
     * focus off, and focus is still on but there is nothing fittable yet. The
     * surface must not confuse them — treating the second as a user toggle makes
     * a transient roster gap force-resume following and snatch the camera back
     * from someone who deliberately panned away. Which is why setConvoyFit takes
     * the mode as its own argument rather than inferring intent from null points.
     */
    @Test
    fun `follow-self from an empty roster is not the same as focus being switched off`() {
        // Focus ON, but nobody's position is known this tick.
        val gap = ConvoyFocusPlanner.plan(ConvoyFocusMode.Convoy, me, emptyList())
        // Focus OFF, with a full roster available.
        val off = ConvoyFocusPlanner.plan(ConvoyFocusMode.Me, me, listOf(other))

        // Both yield the SAME plan — that is the whole point. The plan is
        // therefore not sufficient information for the surface to decide whether
        // the user acted, and any implementation that reads intent out of it is
        // wrong for one of these two cases.
        assertEquals(ConvoyCameraPlan.FollowSelf, gap)
        assertEquals(ConvoyCameraPlan.FollowSelf, off)
        assertEquals(gap, off)

        // What separates the two cases is therefore the MODE, not the plan —
        // focus stays ON through a data gap, and only the user's choice turns it
        // off. That is why `setConvoyFit` takes the mode as its own argument
        // instead of inferring intent from null points; the assertions above are
        // what pins the property down, so there is deliberately no further
        // assertion here comparing two enum constants (which could never fail).
    }
}
