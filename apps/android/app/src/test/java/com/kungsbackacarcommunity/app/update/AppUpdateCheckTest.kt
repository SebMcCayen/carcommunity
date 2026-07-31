package com.kungsbackacarcommunity.app.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The check as the shell runs it, with the Play layer swapped for a fake.
 *
 * The point of these tests is the DEGRADATION: a device Play knows nothing
 * about — a debug build pushed over adb, a sideloaded APK, an AOSP device with
 * no Play Store — is the everyday case during development, and it must be
 * indistinguishable from "no update available". Never a crash, never an error
 * in front of the member.
 */
class AppUpdateCheckTest {

    private val now = 1_700_000_000_000L

    private class FakeSource(
        private val reading: AppUpdateAvailability? = null,
        private val failWith: Exception? = null,
    ) : AppUpdateSource {
        override suspend fun fetch(): AppUpdateAvailability? {
            failWith?.let { throw it }
            return reading
        }

        override fun startFlow(
            launcher: ActivityResultLauncher<IntentSenderRequest>,
            immediate: Boolean,
        ): Boolean = false

        override fun onDownloadComplete(onDownloaded: () -> Unit): () -> Unit = {}

        override fun completeUpdate(): Boolean = false
    }

    private val update =
        AppUpdateAvailability(
            availableVersionCode = 24,
            isFlexibleAllowed = true,
            isImmediateAllowed = true,
            priority = 0,
            isImmediateInProgress = false,
            isDownloaded = false,
        )

    @Test
    fun `an available update is reported with the reading behind it`() = runTest {
        val result = AppUpdateCheck.run(FakeSource(update), dismissal = null, nowMillis = now)
        assertEquals(AppUpdateDecision.FLEXIBLE, result.decision)
        // The shell needs the version code to key the dismissal on.
        assertEquals(24, result.availability?.availableVersionCode)
    }

    // --- non-Play / unavailable installs -----------------------------------

    @Test
    fun `a device with no Play update API at all shows nothing`() {
        // PlayAppUpdateSource.createIfAvailable returns null here.
        runTest {
            val result = AppUpdateCheck.run(source = null, dismissal = null, nowMillis = now)
            assertEquals(AppUpdateDecision.NONE, result.decision)
            assertNull(result.availability)
        }
    }

    @Test
    fun `a source that reports nothing shows nothing`() = runTest {
        val result = AppUpdateCheck.run(FakeSource(null), dismissal = null, nowMillis = now)
        assertEquals(AppUpdateDecision.NONE, result.decision)
        assertNull(result.availability)
    }

    @Test
    fun `a thrown Play error degrades silently instead of propagating`() = runTest {
        // What an adb-installed or sideloaded build actually produces: Play's
        // InstallException (ERROR_APP_NOT_OWNED / ERROR_API_NOT_AVAILABLE).
        // Stood in for here by a checked exception, because the assertion is
        // about the boundary, not about which class arrives at it.
        val result =
            AppUpdateCheck.run(
                FakeSource(failWith = IOException("app not owned")),
                dismissal = null,
                nowMillis = now,
            )
        assertEquals(AppUpdateDecision.NONE, result.decision)
        assertNull(result.availability)
    }

    @Test
    fun `an unchecked failure degrades too`() = runTest {
        val result =
            AppUpdateCheck.run(
                FakeSource(failWith = IllegalStateException("Play services missing")),
                dismissal = null,
                nowMillis = now,
            )
        assertEquals(AppUpdateDecision.NONE, result.decision)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        // Turning a cancelled coroutine into "no update" would break
        // structured concurrency: the check would appear to finish.
        try {
            AppUpdateCheck.run(
                FakeSource(failWith = CancellationException("shell left composition")),
                dismissal = null,
                nowMillis = now,
            )
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message?.contains("shell left composition") == true)
        }
    }

    // --- the throttle, through the whole check -----------------------------

    @Test
    fun `a recent dismissal of the same version suppresses the prompt`() = runTest {
        val result =
            AppUpdateCheck.run(
                FakeSource(update),
                dismissal = AppUpdateDismissal(versionCode = 24, atMillis = now - 1),
                nowMillis = now,
            )
        assertEquals(AppUpdateDecision.NONE, result.decision)
        // Still reported, so a later decision has the reading available.
        assertEquals(24, result.availability?.availableVersionCode)
    }
}
