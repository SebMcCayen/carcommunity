package com.kungsbackacarcommunity.app.location

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the notification "Stop sharing" action reaching the
 * backend. The bug this guards: `stopSession()` launched on the SERVICE scope,
 * followed by an immediate `stopSelf()`, is cancelled by `onDestroy()`'s
 * `scope.cancel()` before it is ever dispatched — the local service stops while
 * the session stays ACTIVE server-side until its 1/2/4-hour expiry.
 */
class LiveSharingStopTest {

    /**
     * Demonstrates the original defect's mechanism directly, so the reason
     * [LiveSharingStop] exists cannot be refactored away by accident.
     *
     * This is the shape the service used to have: launch on a scope, then stop,
     * which cancels that scope. The call overwhelmingly never runs at all.
     */
    @Test
    fun `launching on a scope that is then cancelled loses the call`() {
        // StandardTestDispatcher queues rather than running eagerly, which is
        // exactly the real ordering: launch() only schedules, and onDestroy()'s
        // cancel() lands before the dispatcher ever gets to the body. Making it
        // explicit rather than sleeping on Dispatchers.IO keeps the assertion
        // deterministic instead of a race the test hopes to win.
        val scheduler = TestCoroutineScheduler()
        val serviceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var reached = false

        val job =
            serviceScope.launch {
                reached = true
            }
        serviceScope.cancel() // what onDestroy() does
        scheduler.advanceUntilIdle()

        assertTrue("the launched call should have been cancelled", job.isCancelled)
        assertFalse("stopSession() must never have reached the backend", reached)
    }

    @Test
    fun `runs the stop and then finishes`() = runTest {
        val order = mutableListOf<String>()
        LiveSharingStop.run(
            stopSession = {
                delay(50)
                order += "stopSession"
            },
            finish = { order += "finish" },
        )
        assertEquals(listOf("stopSession", "finish"), order)
    }

    /** A failing callable must still tear the service down. */
    @Test
    fun `finishes when the stop call throws`() = runTest {
        var finished = 0
        LiveSharingStop.run(
            stopSession = { throw IllegalStateException("network") },
            finish = { finished++ },
        )
        assertEquals(1, finished)
    }

    /** A hung callable must not keep a stopped service resident forever. */
    @Test
    fun `finishes when the stop call never returns`() = runTest {
        var finished = 0
        LiveSharingStop.run(
            stopSession = { awaitCancellation() },
            finish = { finished++ },
            timeoutMillis = 10_000L,
        )
        assertEquals(1, finished)
    }

    /**
     * The point of the fix: run on a scope the service does NOT cancel, and the
     * call survives the service being destroyed underneath it.
     */
    @Test
    fun `survives cancellation of the service scope`() {
        val reached = AtomicInteger(0)
        val trials = 200
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs =
            (0 until trials).map {
                val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val job =
                    processScope.launch {
                        LiveSharingStop.run(
                            stopSession = {
                                delay(1)
                                reached.incrementAndGet()
                            },
                            finish = {},
                        )
                    }
                serviceScope.cancel() // onDestroy() again — must not matter now
                job
            }
        runBlocking { jobs.forEach { it.join() } }
        assertEquals(trials, reached.get())
    }
}
