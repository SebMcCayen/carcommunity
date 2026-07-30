package com.kungsbackacarcommunity.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding a Play reading into the model the decision acts on. */
class AppUpdateAvailabilityTest {

    private fun fromPlay(
        updateState: PlayUpdateState = PlayUpdateState.AVAILABLE,
        installState: PlayInstallState = PlayInstallState.IDLE,
        versionCode: Int = 24,
        flexible: Boolean = true,
        immediate: Boolean = true,
        priority: Int = 0,
    ) = AppUpdateAvailability.fromPlay(
        updateState = updateState,
        installState = installState,
        availableVersionCode = versionCode,
        isFlexibleAllowed = flexible,
        isImmediateAllowed = immediate,
        priority = priority,
    )

    @Test
    fun `an available update is carried through with Play's flags`() {
        val availability = fromPlay(flexible = true, immediate = false, priority = 3)
        assertEquals(24, availability?.availableVersionCode)
        assertEquals(true, availability?.isFlexibleAllowed)
        assertEquals(false, availability?.isImmediateAllowed)
        assertEquals(3, availability?.priority)
        assertFalse(availability?.isDownloaded ?: true)
        assertFalse(availability?.isImmediateInProgress ?: true)
    }

    @Test
    fun `no update and an unknown state both mean nothing to offer`() {
        assertNull(fromPlay(updateState = PlayUpdateState.NOTHING))
    }

    @Test
    fun `a finished download outranks everything else`() {
        // Play reports IN_PROGRESS alongside DOWNLOADED, so the install state
        // has to be read first or the restart offer would never be reached.
        val availability =
            fromPlay(
                updateState = PlayUpdateState.IN_PROGRESS,
                installState = PlayInstallState.DOWNLOADED,
            )
        assertTrue(availability?.isDownloaded ?: false)
    }

    @Test
    fun `work already in flight is left alone`() {
        assertNull(
            fromPlay(
                updateState = PlayUpdateState.AVAILABLE,
                installState = PlayInstallState.WORKING,
            ),
        )
    }

    @Test
    fun `an interrupted flow is resumable only while blocking is allowed`() {
        assertTrue(
            fromPlay(updateState = PlayUpdateState.IN_PROGRESS, immediate = true)
                ?.isImmediateInProgress ?: false,
        )
        assertNull(fromPlay(updateState = PlayUpdateState.IN_PROGRESS, immediate = false))
    }

    @Test
    fun `an untrustworthy version code means nothing to offer`() {
        // Play reports 0 (or a placeholder) when it has no real answer; that
        // is not a value worth keying a week-long throttle on.
        assertNull(fromPlay(versionCode = 0))
        assertNull(fromPlay(versionCode = -1))
    }

    @Test
    fun `priority is clamped to Play's documented range`() {
        assertEquals(0, fromPlay(priority = -7)?.priority)
        assertEquals(
            AppUpdateAvailability.MAX_PRIORITY,
            fromPlay(priority = 99)?.priority,
        )
    }
}
