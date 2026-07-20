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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        val reached = AtomicInteger(0)
        val trials = 200
        repeat(trials) {
            val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            serviceScope.launch {
                delay(1) // any real network call suspends at least this long
                reached.incrementAndGet()
            }
            serviceScope.cancel() // what onDestroy() does
        }
        Thread.sleep(200)
        assertTrue(
            "expected the cancelled-scope pattern to lose nearly every call, " +
                "reached=${reached.get()} of $trials",
            reached.get() < trials / 2,
        )
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
