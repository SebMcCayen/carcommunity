package com.kungsbackacarcommunity.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure update-prompt decision: comparison, dismissal window, fail-safe. */
class AppUpdatePolicyTest {

    private val now = 1_700_000_000_000L
    private val week = AppUpdatePolicy.DISMISS_SUPPRESSION_MILLIS

    private fun config(
        latest: Int = 23,
        minimum: Int = 0,
        name: String? = "0.8.12",
    ) = AppVersionConfig(
        latestVersionCode = latest,
        latestVersionName = name,
        minimumSupportedVersionCode = minimum,
    )

    private fun decide(
        config: AppVersionConfig?,
        current: Int,
        dismissal: AppUpdateDismissal? = null,
        nowMillis: Long = now,
    ) = AppUpdatePolicy.decide(config, current, dismissal, nowMillis)

    // --- the comparison ----------------------------------------------------

    @Test
    fun `an older build is prompted to update`() {
        assertEquals(AppUpdateDecision.OPTIONAL, decide(config(latest = 23), current = 22))
    }

    @Test
    fun `the current build is not prompted`() {
        assertEquals(AppUpdateDecision.NONE, decide(config(latest = 23), current = 23))
    }

    @Test
    fun `a build newer than the server value is not prompted`() {
        // A local debug build, or a staged rollout that reached the device
        // before the operator updated the record. Never prompt backwards.
        assertEquals(AppUpdateDecision.NONE, decide(config(latest = 23), current = 24))
    }

    @Test
    fun `the comparison is on integers, not version-name strings`() {
        // "0.9.0" sorts AFTER "0.10.0" as text; versionCode 90 is genuinely
        // behind versionCode 100, and that is what decides.
        val newer = config(latest = 100, name = "0.10.0")
        assertEquals(AppUpdateDecision.OPTIONAL, decide(newer, current = 90))
        val older = config(latest = 90, name = "0.9.0")
        assertEquals(AppUpdateDecision.NONE, decide(older, current = 100))
    }

    // --- fail safe ---------------------------------------------------------

    @Test
    fun `a missing or unreadable config shows nothing`() {
        assertEquals(AppUpdateDecision.NONE, decide(null, current = 1))
        // Even for an absurdly old build: with no config there is nothing to
        // compare against, so the app behaves as if the feature did not exist.
        assertEquals(AppUpdateDecision.NONE, decide(null, current = 0))
    }

    @Test
    fun `a latest version of zero never prompts`() {
        assertEquals(AppUpdateDecision.NONE, decide(config(latest = 0), current = 23))
    }

    // --- dismissal policy --------------------------------------------------

    @Test
    fun `a fresh dismissal suppresses the prompt`() {
        val dismissal = AppUpdateDismissal(versionCode = 23, atMillis = now)
        assertEquals(
            AppUpdateDecision.NONE,
            decide(config(latest = 23), current = 22, dismissal = dismissal),
        )
    }

    @Test
    fun `a dismissal still suppresses just before the window closes`() {
        val dismissal = AppUpdateDismissal(versionCode = 23, atMillis = now - (week - 1))
        assertEquals(
            AppUpdateDecision.NONE,
            decide(config(latest = 23), current = 22, dismissal = dismissal),
        )
    }

    @Test
    fun `the prompt returns once the window has elapsed`() {
        val dismissal = AppUpdateDismissal(versionCode = 23, atMillis = now - week)
        assertEquals(
            AppUpdateDecision.OPTIONAL,
            decide(config(latest = 23), current = 22, dismissal = dismissal),
        )
    }

    @Test
    fun `a newer release re-prompts immediately despite a fresh dismissal`() {
        // Dismissing version 23 must not silence version 24.
        val dismissal = AppUpdateDismissal(versionCode = 23, atMillis = now)
        assertEquals(
            AppUpdateDecision.OPTIONAL,
            decide(config(latest = 24), current = 22, dismissal = dismissal),
        )
    }

    @Test
    fun `a dismissal recorded against a later version still suppresses`() {
        // Can only happen if the server value went backwards; treat the newer
        // dismissal as covering it rather than nagging.
        val dismissal = AppUpdateDismissal(versionCode = 30, atMillis = now)
        assertEquals(
            AppUpdateDecision.NONE,
            decide(config(latest = 23), current = 22, dismissal = dismissal),
        )
    }

    @Test
    fun `a device clock that moved backwards does not resurrect the prompt`() {
        val dismissal = AppUpdateDismissal(versionCode = 23, atMillis = now + week)
        assertEquals(
            AppUpdateDecision.NONE,
            decide(config(latest = 23), current = 22, dismissal = dismissal),
        )
    }

    @Test
    fun `dismissal never suppresses the unsupported-version block`() {
        val dismissal = AppUpdateDismissal(versionCode = 23, atMillis = now)
        assertEquals(
            AppUpdateDecision.REQUIRED,
            decide(config(latest = 23, minimum = 22), current = 21, dismissal = dismissal),
        )
    }

    // --- the separate, default-inert blocking path -------------------------

    @Test
    fun `the block is inert at the default minimum of zero`() {
        assertEquals(AppUpdateDecision.OPTIONAL, decide(config(latest = 23, minimum = 0), current = 1))
        assertEquals(AppUpdateDecision.NONE, decide(config(latest = 1, minimum = 0), current = 1))
    }

    @Test
    fun `a build below a deliberately raised minimum is blocked`() {
        assertEquals(
            AppUpdateDecision.REQUIRED,
            decide(config(latest = 23, minimum = 20), current = 19),
        )
    }

    @Test
    fun `a build exactly at the minimum is not blocked`() {
        assertEquals(
            AppUpdateDecision.OPTIONAL,
            decide(config(latest = 23, minimum = 20), current = 20),
        )
    }

    @Test
    fun `the latest build is never blocked`() {
        assertEquals(
            AppUpdateDecision.NONE,
            decide(config(latest = 23, minimum = 23), current = 23),
        )
    }

    @Test
    fun `the suppression window is one week`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, AppUpdatePolicy.DISMISS_SUPPRESSION_MILLIS)
    }
}
