package com.kungsbackacarcommunity.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure update-prompt decision: what Play's reading turns into, the
 * throttle window, the default-inert escalation, and the fail-safe.
 */
class AppUpdatePolicyTest {

    private val now = 1_700_000_000_000L
    private val week = AppUpdatePolicy.DISMISS_SUPPRESSION_MILLIS

    private fun available(
        versionCode: Int = 24,
        flexible: Boolean = true,
        immediate: Boolean = true,
        priority: Int = 0,
        immediateInProgress: Boolean = false,
        downloaded: Boolean = false,
    ) = AppUpdateAvailability(
        availableVersionCode = versionCode,
        isFlexibleAllowed = flexible,
        isImmediateAllowed = immediate,
        priority = priority,
        isImmediateInProgress = immediateInProgress,
        isDownloaded = downloaded,
    )

    private fun decide(
        availability: AppUpdateAvailability?,
        dismissal: AppUpdateDismissal? = null,
        nowMillis: Long = now,
    ) = AppUpdatePolicy.decide(availability, dismissal, nowMillis)

    // --- the ordinary case -------------------------------------------------

    @Test
    fun `an available update is offered as the background flow`() {
        assertEquals(AppUpdateDecision.FLEXIBLE, decide(available()))
    }

    @Test
    fun `an update the background flow may not install is not offered`() {
        // Offering it would produce a prompt whose only button cannot act.
        assertEquals(
            AppUpdateDecision.NONE,
            decide(available(flexible = false, immediate = false)),
        )
    }

    // --- fail safe ---------------------------------------------------------

    @Test
    fun `no reading from Play shows nothing`() {
        // The non-Play install, the offline device, the thrown
        // InstallException — every one of them arrives here as null.
        assertEquals(AppUpdateDecision.NONE, decide(null))
        assertEquals(
            AppUpdateDecision.NONE,
            decide(null, dismissal = AppUpdateDismissal(1, now - week * 100)),
        )
    }

    // --- throttling --------------------------------------------------------

    @Test
    fun `a fresh dismissal suppresses the prompt`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now)
        assertEquals(AppUpdateDecision.NONE, decide(available(24), dismissal = dismissal))
    }

    @Test
    fun `a dismissal still suppresses just before the window closes`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now - (week - 1))
        assertEquals(AppUpdateDecision.NONE, decide(available(24), dismissal = dismissal))
    }

    @Test
    fun `the prompt returns once the window has elapsed`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now - week)
        assertEquals(AppUpdateDecision.FLEXIBLE, decide(available(24), dismissal = dismissal))
    }

    @Test
    fun `a newer release re-prompts immediately despite a fresh dismissal`() {
        // Dismissing version 24 must not silence version 25.
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now)
        assertEquals(AppUpdateDecision.FLEXIBLE, decide(available(25), dismissal = dismissal))
    }

    @Test
    fun `a dismissal recorded against a later version still suppresses`() {
        // Can only happen if Play's offer went backwards (a halted rollout);
        // treat the newer dismissal as covering it rather than nagging.
        val dismissal = AppUpdateDismissal(versionCode = 30, atMillis = now)
        assertEquals(AppUpdateDecision.NONE, decide(available(24), dismissal = dismissal))
    }

    @Test
    fun `a device clock that moved backwards does not resurrect the prompt`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now + week)
        assertEquals(AppUpdateDecision.NONE, decide(available(24), dismissal = dismissal))
    }

    @Test
    fun `the suppression window is one week`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, AppUpdatePolicy.DISMISS_SUPPRESSION_MILLIS)
    }

    // --- the default-inert blocking path -----------------------------------

    @Test
    fun `the blocking path is inert at Play's default priority`() {
        // Play defaults inAppUpdatePriority to 0 on every release, so nothing
        // blocks unless someone deliberately publishes at the top of the scale.
        for (priority in 0 until AppUpdatePolicy.IMMEDIATE_PRIORITY_THRESHOLD) {
            assertEquals(
                "priority $priority must not block",
                AppUpdateDecision.FLEXIBLE,
                decide(available(priority = priority)),
            )
        }
    }

    @Test
    fun `a top-priority release blocks`() {
        assertEquals(
            AppUpdateDecision.IMMEDIATE,
            decide(available(priority = AppUpdatePolicy.IMMEDIATE_PRIORITY_THRESHOLD)),
        )
    }

    @Test
    fun `a dismissal never suppresses the blocking path`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now)
        assertEquals(
            AppUpdateDecision.IMMEDIATE,
            decide(
                available(priority = AppUpdatePolicy.IMMEDIATE_PRIORITY_THRESHOLD),
                dismissal = dismissal,
            ),
        )
    }

    @Test
    fun `a top-priority release falls back to the background flow if blocking is not allowed`() {
        assertEquals(
            AppUpdateDecision.FLEXIBLE,
            decide(
                available(
                    immediate = false,
                    priority = AppUpdatePolicy.IMMEDIATE_PRIORITY_THRESHOLD,
                ),
            ),
        )
    }

    @Test
    fun `an interrupted blocking flow is resumed and cannot be dismissed away`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now)
        assertEquals(
            AppUpdateDecision.IMMEDIATE,
            decide(available(immediateInProgress = true), dismissal = dismissal),
        )
    }

    // --- the finished download ---------------------------------------------

    @Test
    fun `a downloaded update asks for the restart, whatever else is true`() {
        val dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now)
        assertEquals(
            AppUpdateDecision.AWAITING_RESTART,
            decide(available(downloaded = true), dismissal = dismissal),
        )
        // Even at top priority: the bytes are already here, so a restart is
        // strictly less disruptive than restarting the whole Play flow.
        assertEquals(
            AppUpdateDecision.AWAITING_RESTART,
            decide(
                available(
                    downloaded = true,
                    priority = AppUpdatePolicy.IMMEDIATE_PRIORITY_THRESHOLD,
                ),
            ),
        )
    }
}
