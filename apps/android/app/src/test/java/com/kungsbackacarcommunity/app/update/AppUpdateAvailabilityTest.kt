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

    /** What the fold should answer, expressed as the flags that matter. */
    private enum class Folded {
        /** Nothing to put in front of the member. */
        NOTHING,

        /** A plain offer: neither downloaded nor a blocking flow to resume. */
        OFFER,

        /** Downloaded, pending the restart that installs it. */
        DOWNLOADED,

        /** A blocking flow that has to be resumed. */
        RESUME_BLOCKING,
    }

    private data class Case(
        val updateState: PlayUpdateState,
        val installState: PlayInstallState,
        val priority: Int,
        val immediate: Boolean,
        val expected: Folded,
        val why: String,
    )

    /**
     * THE WHOLE MATRIX. Play's `updateAvailability()` and `installStatus()` are
     * independent axes, so every combination of the two has to have a stated
     * answer — and the answer has to come from a `when` that covers them all,
     * not from ordered early returns where one axis can swallow the other.
     *
     * The rows that matter most are DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
     * paired with PENDING / DOWNLOADING / INSTALLING (all three fold to
     * [PlayInstallState.WORKING]): that IS the ordinary shape of an interrupted
     * blocking update, and an earlier version of this fold returned null for
     * the whole row, which made [AppUpdateAvailability.isImmediateInProgress]
     * unreachable in exactly the case Play expects the app to resume from.
     */
    @Test
    fun `every availability and install-status combination has a stated answer`() {
        val blocking = AppUpdateAvailability.MAX_PRIORITY
        val cases =
            listOf(
                // No update behind the reading: nothing to say, whatever the
                // install status claims — except bytes that are already down.
                Case(
                    PlayUpdateState.NOTHING, PlayInstallState.IDLE, 0, true,
                    Folded.NOTHING, "no update, nothing in flight",
                ),
                Case(
                    PlayUpdateState.NOTHING, PlayInstallState.WORKING, 0, true,
                    Folded.NOTHING, "no update behind the bytes: contradictory, stay quiet",
                ),
                Case(
                    PlayUpdateState.NOTHING, PlayInstallState.DOWNLOADED, 0, true,
                    Folded.DOWNLOADED, "bytes on disk are worth a restart offer regardless",
                ),
                // A newer build, nothing started yet.
                Case(
                    PlayUpdateState.AVAILABLE, PlayInstallState.IDLE, 0, true,
                    Folded.OFFER, "the ordinary case",
                ),
                Case(
                    PlayUpdateState.AVAILABLE, PlayInstallState.WORKING, 0, true,
                    Folded.NOTHING, "work in flight is left alone",
                ),
                Case(
                    PlayUpdateState.AVAILABLE, PlayInstallState.DOWNLOADED, 0, true,
                    Folded.DOWNLOADED, "downloaded outranks the offer",
                ),
                // An update this app started, on a release Play would NOT let
                // us block with: that is the flexible download, and resuming it
                // as a full-screen takeover is the thing the flexible flow
                // exists to avoid.
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.IDLE, 0, true,
                    Folded.NOTHING, "flexible flow, nothing blocking to resume",
                ),
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.WORKING, 0, true,
                    Folded.NOTHING, "flexible download in flight: leave it downloading",
                ),
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.DOWNLOADED, 0, true,
                    Folded.DOWNLOADED, "flexible download finished",
                ),
                // The same rows on a release published at the blocking
                // priority — the only way this app ever starts a blocking flow.
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.IDLE, blocking, true,
                    Folded.RESUME_BLOCKING, "interrupted by a process death: status reset",
                ),
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.WORKING, blocking, true,
                    Folded.RESUME_BLOCKING, "pending/downloading/installing: still resumable",
                ),
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.DOWNLOADED, blocking, true,
                    Folded.DOWNLOADED, "already downloaded: install it, do not re-block",
                ),
                // Blocking withdrawn by Play: there is nothing actionable left.
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.WORKING, blocking, false,
                    Folded.NOTHING, "blocking not allowed, so not resumable",
                ),
                Case(
                    PlayUpdateState.IN_PROGRESS, PlayInstallState.IDLE, blocking, false,
                    Folded.NOTHING, "blocking not allowed, so not resumable",
                ),
            )

        for (case in cases) {
            val availability =
                fromPlay(
                    updateState = case.updateState,
                    installState = case.installState,
                    immediate = case.immediate,
                    priority = case.priority,
                )
            val label =
                "${case.updateState} + ${case.installState} " +
                    "(priority=${case.priority}, immediateAllowed=${case.immediate}): ${case.why}"
            val folded =
                when {
                    availability == null -> Folded.NOTHING
                    availability.isDownloaded -> Folded.DOWNLOADED
                    availability.isImmediateInProgress -> Folded.RESUME_BLOCKING
                    else -> Folded.OFFER
                }
            assertEquals(label, case.expected, folded)
        }
    }

    @Test
    fun `a blocking update mid-download is resumed rather than swallowed`() {
        // Spelled out on its own because it is the one the whole fold turns on:
        // Play reporting DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS while the
        // download is still moving must NOT read as "nothing to do".
        val availability =
            fromPlay(
                updateState = PlayUpdateState.IN_PROGRESS,
                installState = PlayInstallState.WORKING,
                immediate = true,
                priority = AppUpdateAvailability.MAX_PRIORITY,
            )
        assertTrue(availability?.isImmediateInProgress ?: false)
        assertEquals(
            AppUpdateDecision.IMMEDIATE,
            AppUpdatePolicy.decide(availability, dismissal = null, nowMillis = 0L),
        )
    }

    @Test
    fun `an interrupted flow is resumable only while blocking is allowed`() {
        assertTrue(
            fromPlay(
                updateState = PlayUpdateState.IN_PROGRESS,
                immediate = true,
                priority = AppUpdateAvailability.MAX_PRIORITY,
            )?.isImmediateInProgress ?: false,
        )
        assertNull(
            fromPlay(
                updateState = PlayUpdateState.IN_PROGRESS,
                immediate = false,
                priority = AppUpdateAvailability.MAX_PRIORITY,
            ),
        )
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
