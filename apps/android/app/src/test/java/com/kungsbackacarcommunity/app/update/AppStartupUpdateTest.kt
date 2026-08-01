package com.kungsbackacarcommunity.app.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The startup gate's decision logic: which update verdicts block the app from
 * composing its shell, and — just as important — which do NOT, so the gate can
 * never lock a member out of a working app.
 *
 * The composables ([rememberAppStartupUpdateGate], [ForcedUpdateGate]) are Play-
 * and Compose-bound and are exercised on device; the part that DECIDES is pure
 * and lives here, including the end-to-end path through [AppUpdateCheck] with
 * the Play layer faked, because that is the seam an outdated client actually
 * travels on the first cold launch.
 */
class AppStartupUpdateTest {

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

    /** A release published at the top of Play's priority scale: a forced update. */
    private val blockingUpdate =
        AppUpdateAvailability(
            availableVersionCode = 30,
            isFlexibleAllowed = true,
            isImmediateAllowed = true,
            priority = AppUpdateAvailability.MAX_PRIORITY,
            isImmediateInProgress = false,
            isDownloaded = false,
        )

    /** An ordinary newer build: offered, never mandatory. */
    private val flexibleUpdate =
        AppUpdateAvailability(
            availableVersionCode = 30,
            isFlexibleAllowed = true,
            isImmediateAllowed = false,
            priority = 0,
            isImmediateInProgress = false,
            isDownloaded = false,
        )

    // --- the pure mapping ---------------------------------------------------

    @Test
    fun `only a blocking update gates startup`() {
        assertEquals(true, AppStartupUpdate.gates(AppUpdateDecision.IMMEDIATE))
        assertEquals(false, AppStartupUpdate.gates(AppUpdateDecision.FLEXIBLE))
        assertEquals(false, AppStartupUpdate.gates(AppUpdateDecision.AWAITING_RESTART))
        assertEquals(false, AppStartupUpdate.gates(AppUpdateDecision.NONE))
    }

    @Test
    fun `verdict is FORCED only for a blocking update, CLEAR otherwise`() {
        assertEquals(AppStartupUpdateGate.FORCED, AppStartupUpdate.verdict(AppUpdateDecision.IMMEDIATE))
        assertEquals(AppStartupUpdateGate.CLEAR, AppStartupUpdate.verdict(AppUpdateDecision.FLEXIBLE))
        assertEquals(
            AppStartupUpdateGate.CLEAR,
            AppStartupUpdate.verdict(AppUpdateDecision.AWAITING_RESTART),
        )
        assertEquals(AppStartupUpdateGate.CLEAR, AppStartupUpdate.verdict(AppUpdateDecision.NONE))
    }

    // --- end-to-end through the check the shell runs ------------------------

    @Test
    fun `a mandatory release gates the shell`() = runTest {
        val result = AppUpdateCheck.run(FakeSource(blockingUpdate), dismissal = null, nowMillis = now)
        assertEquals(AppUpdateDecision.IMMEDIATE, result.decision)
        assertEquals(AppStartupUpdateGate.FORCED, AppStartupUpdate.verdict(result.decision))
    }

    @Test
    fun `an ordinary newer build does not gate the shell`() = runTest {
        val result = AppUpdateCheck.run(FakeSource(flexibleUpdate), dismissal = null, nowMillis = now)
        assertEquals(AppUpdateDecision.FLEXIBLE, result.decision)
        assertEquals(AppStartupUpdateGate.CLEAR, AppStartupUpdate.verdict(result.decision))
    }

    @Test
    fun `no update available does not gate the shell`() = runTest {
        val result = AppUpdateCheck.run(FakeSource(null), dismissal = null, nowMillis = now)
        assertEquals(AppStartupUpdateGate.CLEAR, AppStartupUpdate.verdict(result.decision))
    }

    @Test
    fun `a Play failure during the check leaves startup CLEAR, never locked out`() = runTest {
        // The whole reason the gate is fail-safe: the mechanism meant to keep an
        // outdated client out of a broken shell must never itself become the
        // reason a WORKING client cannot start. A thrown Play read (the everyday
        // non-Play install) degrades to CLEAR — the app composes as normal.
        val result =
            AppUpdateCheck.run(
                FakeSource(failWith = IOException("app not owned")),
                dismissal = null,
                nowMillis = now,
            )
        assertEquals(AppUpdateDecision.NONE, result.decision)
        assertEquals(AppStartupUpdateGate.CLEAR, AppStartupUpdate.verdict(result.decision))
    }

    // --- the fail-safe wrap, exercised directly -----------------------------
    //
    // AppUpdateCheck already swallows a Play read that throws, so to prove the
    // gate's OWN guard — the one that has to hold if the check itself ever
    // throws (a future policy change, a runtime edge) — the check is stubbed to
    // throw / hang / report a reading, with no Play or Compose in the way.

    @Test
    fun `resolve treats a thrown check as CLEAR rather than crashing`() = runTest {
        val gate =
            AppStartupUpdate.resolve { throw IllegalStateException("policy blew up") }
        assertEquals(AppStartupUpdateGate.CLEAR, gate)
    }

    @Test
    fun `resolve treats a check that outruns the timeout as CLEAR`() = runTest {
        val gate =
            AppStartupUpdate.resolve(timeoutMillis = 1_000L) {
                // Longer than the bound: the gate must proceed, not hang startup.
                delay(10_000L)
                AppUpdateCheckResult(AppUpdateDecision.IMMEDIATE, null)
            }
        assertEquals(AppStartupUpdateGate.CLEAR, gate)
    }

    @Test
    fun `resolve reports FORCED for a blocking reading`() = runTest {
        val gate =
            AppStartupUpdate.resolve {
                AppUpdateCheckResult(AppUpdateDecision.IMMEDIATE, blockingUpdate)
            }
        assertEquals(AppStartupUpdateGate.FORCED, gate)
    }

    @Test
    fun `resolve reports CLEAR for a flexible reading`() = runTest {
        val gate =
            AppStartupUpdate.resolve {
                AppUpdateCheckResult(AppUpdateDecision.FLEXIBLE, flexibleUpdate)
            }
        assertEquals(AppStartupUpdateGate.CLEAR, gate)
    }

    @Test
    fun `resolve re-throws cancellation instead of swallowing it`() = runTest {
        // Swallowing cancellation into "CLEAR" would break structured
        // concurrency: leaving the composition would look like a finished check.
        try {
            AppStartupUpdate.resolve {
                throw CancellationException("left composition")
            }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message?.contains("left composition") == true)
        }
    }
}
