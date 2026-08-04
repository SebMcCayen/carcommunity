package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the top Android crash cluster: unbounded re-entrancy in
 * the puck-bearing-arrow swap ([MapboxMapSurface.showPuckBearingArrow]).
 *
 * The real bug: `map.location.updateSettings { locationPuck = … }` re-initialises
 * the location component SYNCHRONOUSLY and, in doing so, re-fires the bearing
 * indicator listener that asked for the swap. With the idempotency flag raised
 * only AFTER the swap returned, that re-fired listener saw the flag still `false`,
 * asked for the swap again, re-fired again… recursing without bound — the "Input
 * dispatching timed out" ANR, and the native `libmapbox-maps.so` SIGSEGV/SIGABRT
 * family from the re-entrant `setCamera` corrupting camera state during the storm.
 *
 * The Mapbox `MapView`/location types can't be faked in a JVM test, so the fix's
 * load-bearing part — the set-BEFORE-apply guard ordering — is factored into the
 * pure [applyPuckBearingArrowOnce] helper, and that is what these pin. A re-entrant
 * `applySwap` here stands in for `updateSettings` re-firing the listener.
 */
class PuckBearingArrowReentrancyTest {
    /**
     * The fix: even when `applySwap` synchronously re-invokes the guard (as a real
     * `updateSettings` re-fires the bearing listener), the swap runs EXACTLY ONCE.
     * The depth cap keeps a broken ordering from becoming a `StackOverflowError` —
     * it surfaces as a count instead, so this asserts cleanly.
     */
    @Test
    fun reentrantSwapRunsExactlyOnce() {
        var shown = false
        var applyCount = 0
        // Models one full trip through the guard: a real caller re-enters it when
        // `updateSettings` re-fires the bearing listener, which is exactly the
        // `invoke()` re-entry from inside `applySwap` below.
        fun invoke() {
            applyPuckBearingArrowOnce(
                isShown = { shown },
                markShown = { shown = true },
                markNotShown = { shown = false },
                applySwap = {
                    applyCount++
                    // Synchronous re-entry, exactly like the re-fired listener.
                    // Depth-capped so a broken ordering fails as a count, not a
                    // StackOverflowError.
                    if (applyCount < 100) invoke()
                },
            )
        }
        invoke()
        assertEquals("swap must apply exactly once under re-entrancy", 1, applyCount)
        assertTrue(shown)
    }

    /** Once the swap has landed, later fixes are cheap no-ops (one arrow per map). */
    @Test
    fun succeedsOnceThenNoOps() {
        var shown = false
        var applyCount = 0
        val call = {
            applyPuckBearingArrowOnce(
                isShown = { shown },
                markShown = { shown = true },
                markNotShown = { shown = false },
                applySwap = { applyCount++ },
            )
        }
        call()
        call()
        call()
        assertEquals(1, applyCount)
        assertTrue(shown)
    }

    /**
     * A swap that genuinely THROWS clears the flag again, so the next heading
     * retries rather than stranding the plain dot for the session — the retry
     * semantics the original `.onSuccess` ordering also had, preserved by the fix.
     */
    @Test
    fun failingSwapClearsTheFlagSoNextCallRetries() {
        var shown = false
        var attempts = 0
        val call = {
            applyPuckBearingArrowOnce(
                isShown = { shown },
                markShown = { shown = true },
                markNotShown = { shown = false },
                applySwap = {
                    attempts++
                    throw RuntimeException("native swap failed")
                },
            )
        }
        call()
        assertFalse("a failed swap must re-arm", shown)
        assertEquals(1, attempts)
        call()
        assertEquals("next fix retries after a failure", 2, attempts)
    }

    /**
     * Teeth: the OLD "raise the flag only once the swap LANDED" ordering re-enters
     * unboundedly under the very same re-entrant swap — which is the ANR this fix
     * removes. If the production helper regressed to that ordering,
     * [reentrantSwapRunsExactlyOnce] would see this many applies and fail.
     */
    @Test
    fun oldOrderingWouldRecurse_provingTheTestHasTeeth() {
        var shown = false
        var applyCount = 0
        fun oldOrdering() {
            if (shown) return
            runCatching {
                applyCount++
                if (applyCount < 50) oldOrdering() // re-fired listener, pre-fix
            }.onSuccess { shown = true } // flag raised only AFTER the swap — the bug
        }
        oldOrdering()
        assertEquals("old ordering recurses far past one apply", 50, applyCount)
    }
}
