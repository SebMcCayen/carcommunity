package com.kungsbackacarcommunity.app.navigation.turnbyturn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map-home → turn-by-turn handoff, i.e. the white flash.
 *
 * The load-bearing test here is [navMapIsNotMountedUntilTheVeilIsOpaque]: it
 * pins the ONE property that makes the flash impossible rather than merely
 * unlikely. Everything else is the surrounding state machine.
 */
class NavHandoffTest {
    /**
     * **The regression test.**
     *
     * The flash was a brand-new `MapView` — a second GL surface, which paints
     * blank frames for the whole of its first style load — being mounted over
     * the shell's map the instant navigation started. A `MapView` is
     * `SurfaceView`-backed, so it is punched through the window rather than
     * composited with anything drawn above it: it CANNOT be hidden by fading a
     * veil in on top of it. The only way to not show those frames is to not have
     * the map on screen until the veil is already opaque.
     *
     * So: the map must be absent for exactly the fade-in phase, and present
     * afterwards. If someone later "simplifies" this by mounting the map up
     * front and relying on the veil's alpha, this fails.
     */
    @Test
    fun navMapIsNotMountedUntilTheVeilIsOpaque() {
        assertFalse(
            "The nav MapView must NOT be mounted while the veil is still fading " +
                "in — a SurfaceView cannot be hidden by a veil drawn over it, so " +
                "mounting here is exactly what showed the blank GL frames.",
            NavHandoffPhase.VeilIn.mapMounted,
        )
        // From the moment the veil is opaque onward the map is mounted: the
        // blank frames land behind it, and the reveal has something to reveal.
        assertTrue(NavHandoffPhase.Loading.mapMounted)
        assertTrue(NavHandoffPhase.Revealing.mapMounted)
        assertTrue(NavHandoffPhase.Ready.mapMounted)
    }

    /**
     * The other half of the guarantee: the veil must be fully OPAQUE for the
     * whole window in which the map is mounted but not yet drawn.
     *
     * Mounting the map behind a veil that is only half-faded would show the same
     * blank frames through it, so [NavHandoffPhase.Loading] — the phase the map
     * mounts into — must target full opacity, not a partial value.
     */
    @Test
    fun veilIsFullyOpaqueForEveryPhaseThatCanShowBlankFrames() {
        assertEquals(1f, NavHandoffPhase.VeilIn.veilTargetAlpha, 0f)
        assertEquals(1f, NavHandoffPhase.Loading.veilTargetAlpha, 0f)
    }

    /**
     * **There must be a fade IN, not a cut to opaque.**
     *
     * The veil starts transparent and its first phase targets opaque, so the
     * user sees the map home dissolve away rather than being replaced. This is
     * easy to lose without noticing: an animation that initialises to its own
     * first target value — which is exactly what `animateFloatAsState` does —
     * would begin at [NavHandoffPhase.VeilIn]'s `1f` and snap, leaving only the
     * fade-out animating while the KDoc still promised a dissolve. The UI
     * therefore seeds its animation from [NavHandoff.VEIL_START_ALPHA], and this
     * asserts that value genuinely differs from what the first phase drives to.
     */
    @Test
    fun veilStartsTransparentSoTheEntryActuallyFadesIn() {
        assertEquals(0f, NavHandoff.VEIL_START_ALPHA, 0f)
        assertTrue(
            "The veil's starting alpha must differ from VeilIn's target, or the " +
                "entry is a hard cut to opaque rather than a dissolve",
            NavHandoff.VEIL_START_ALPHA != NavHandoffPhase.VeilIn.veilTargetAlpha,
        )
    }

    /** Once the map has drawn, the veil goes away entirely. */
    @Test
    fun veilClearsAfterTheStyleIsUp() {
        assertEquals(0f, NavHandoffPhase.Revealing.veilTargetAlpha, 0f)
        assertEquals(0f, NavHandoffPhase.Ready.veilTargetAlpha, 0f)
        // Not merely transparent — not composed at all, so it cannot swallow a
        // touch meant for the map (the failure PR #464 fixed).
        assertFalse(NavHandoffPhase.Ready.veilVisible)
        assertTrue(NavHandoffPhase.VeilIn.veilVisible)
        assertTrue(NavHandoffPhase.Loading.veilVisible)
        assertTrue(NavHandoffPhase.Revealing.veilVisible)
    }

    /** The normal path: the style is still loading when the fade-in ends. */
    @Test
    fun fadeInHandsOverToLoadingWhenTheStyleIsNotUpYet() {
        assertEquals(NavHandoffPhase.Loading, NavHandoff.afterVeilIn(styleLoaded = false))
    }

    /**
     * A fast/cached style load can finish DURING the fade-in. Sitting on an
     * opaque veil over an already-drawn map would be a pointless extra beat, so
     * that case reveals immediately.
     */
    @Test
    fun fadeInGoesStraightToRevealWhenTheStyleAlreadyLoaded() {
        assertEquals(NavHandoffPhase.Revealing, NavHandoff.afterVeilIn(styleLoaded = true))
    }

    /** While loading with no style and no timeout, the veil stays put. */
    @Test
    fun loadingHoldsTheVeilUntilSomethingHappens() {
        assertEquals(
            NavHandoffPhase.Loading,
            NavHandoff.whileLoading(styleLoaded = false, timedOut = false),
        )
    }

    /** The style arriving is the normal way out of the veil. */
    @Test
    fun loadingRevealsWhenTheStyleLoads() {
        assertEquals(
            NavHandoffPhase.Revealing,
            NavHandoff.whileLoading(styleLoaded = true, timedOut = false),
        )
    }

    /**
     * The safety valve. A style load that never completes — no network, a bad
     * style URI, a callback the SDK simply never fires — must not strand the
     * user behind an opaque veil forever. Timing out degrades to the OLD
     * behaviour (a possibly-ugly map) rather than to a dead screen.
     */
    @Test
    fun loadingRevealsOnTimeoutEvenWithoutAStyle() {
        assertEquals(
            NavHandoffPhase.Revealing,
            NavHandoff.whileLoading(styleLoaded = false, timedOut = true),
        )
    }

    /**
     * The dissolve borrows the shell's existing tab-crossfade tempo rather than
     * introducing a second transition speed for the same app.
     */
    @Test
    fun fadeMatchesTheShellCrossfadeTempo() {
        assertEquals(200, NavHandoff.FADE_MILLIS)
    }

    /** The timeout has to be longer than the fade, or it would fire mid-dissolve. */
    @Test
    fun styleTimeoutOutlastsTheFade() {
        assertTrue(NavHandoff.STYLE_TIMEOUT_MILLIS > NavHandoff.FADE_MILLIS)
    }
}
