package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.live.LiveCoordinate
import com.kungsbackacarcommunity.app.live.LiveSessionDuration
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import com.kungsbackacarcommunity.app.live.LiveSessionStatus
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionPositionPublisherTest {
    private val epoch = 1_700_000_000_000L

    private fun session(id: String = "a", ready: Boolean = false, expiresAt: Long = epoch + 21_600_000) =
        LiveSessionInfo(
            id, LiveSessionStatus.ACTIVE, LiveSessionDuration.SIX_HOURS, expiresAt,
            discoveryRefreshedAtMillis = if (ready) epoch else null,
        )

    private fun fix(at: Long, latitude: Double = 0.0) =
        LiveCoordinate(latitude, 0.0, Instant.ofEpochMilli(at).toString(), 5.0)

    @Test fun `failed stationary first upload retries before heartbeat and readiness restores normal cadence`() = runTest {
        val times = mutableListOf<Long>()
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            times += testScheduler.currentTime
            if (times.size == 1) throw IllegalStateException("synthetic failure")
        }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(0L, 2_000L), times)
        publisher.observeSession(session(ready = true))
        repeat(12) {
            advanceTimeBy(5_000)
            publisher.accept(fix(epoch + testScheduler.currentTime))
            runCurrent()
        }
        assertEquals(2, times.size)
    }

    @Test fun `HTTP success without discovery proof still retries but cannot hot write forever`() = runTest {
        val times = mutableListOf<Long>()
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            times += testScheduler.currentTime
        }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        repeat(40) {
            advanceTimeBy(5_000)
            publisher.accept(fix(epoch + testScheduler.currentTime))
            runCurrent()
        }
        assertEquals(listOf(0L, 2_000L, 7_000L, 17_000L, 37_000L), times)
        // Retry exhaustion retains ordinary heartbeats rather than disabling
        // publication or resetting the startup budget on each successful call.
        advanceTimeBy(20_000)
        publisher.accept(fix(epoch + testScheduler.currentTime))
        runCurrent()
        assertEquals(6, times.size)
        assertTrue(times.last() >= 217_000L)
    }

    @Test fun `readiness alone cannot suppress retry after a failed first upload`() = runTest {
        var calls = 0
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            calls++
            if (calls == 1) throw IllegalStateException("synthetic failure")
        }
        publisher.observeSession(session(ready = true))
        publisher.accept(fix(epoch))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun `replacement cancels old upload discards pending fix and publishes immediately`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val sent = mutableListOf<LiveCoordinate>()
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            sent += it
            if (sent.size == 1) {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        publisher.accept(fix(epoch + 1, 0.001))
        assertTrue(publisher.observeSession(session("b")))
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, sent.size)
        publisher.accept(fix(epoch, 0.002))
        runCurrent()
        assertEquals(listOf(0.0, 0.002), sent.map { it.latitude })
    }

    @Test fun `slow upload retains only newest pending fix and never overlaps calls`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<Double>()
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            sent += it.latitude
            if (sent.size == 1) gate.await()
        }
        publisher.observeSession(session(ready = true))
        publisher.accept(fix(epoch))
        runCurrent()
        publisher.accept(fix(epoch, 0.001))
        publisher.accept(fix(epoch, 0.002))
        runCurrent()
        assertEquals(listOf(0.0), sent)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0.0, 0.002), sent)
    }

    @Test fun `stop cancels a pending startup retry and rejects later fixes`() = runTest {
        var calls = 0
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) { calls++ }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        publisher.stop()
        publisher.accept(fix(epoch))
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(null, publisher.sessionId)
    }

    @Test fun `absent session pauses and cannot replenish same session startup budget`() = runTest {
        var calls = 0
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) { calls++ }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        repeat(4) {
            advanceTimeBy(10_000)
            publisher.observeSession(null)
            publisher.accept(fix(epoch + testScheduler.currentTime))
            runCurrent()
            val paused = calls
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(paused, calls)
            assertFalse(publisher.observeSession(session()))
            publisher.accept(fix(epoch + testScheduler.currentTime))
            runCurrent()
        }
        assertEquals(LiveSessionPositionPublisher.MAX_STARTUP_ATTEMPTS, calls)
    }

    @Test fun `expired session cannot retry even before service lifecycle tick`() = runTest {
        var calls = 0
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) { calls++ }
        publisher.observeSession(session(expiresAt = epoch + 1_000))
        publisher.accept(fix(epoch))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun `observed stop cancels in flight work and ignores new provider fixes`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            calls++
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        publisher.observeSession(session().copy(status = LiveSessionStatus.STOPPED))
        publisher.accept(fix(epoch, 0.001))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, calls)
    }

    @Test fun `retry never resends a fix that aged out during a slow failed upload`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<LiveCoordinate>()
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) {
            sent += it
            if (sent.size == 1) {
                gate.await()
                throw IllegalStateException("synthetic failure")
            }
        }
        publisher.observeSession(session())
        publisher.accept(fix(epoch))
        runCurrent()
        advanceTimeBy(61_000)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, sent.size)
        publisher.accept(fix(epoch + testScheduler.currentTime))
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(epoch + testScheduler.currentTime, Instant.parse(sent.last().recordedAtIso).toEpochMilli())
    }

    @Test fun `stale future and malformed samples cannot consume first upload or poison retries`() = runTest {
        val sent = mutableListOf<LiveCoordinate>()
        val publisher = LiveSessionPositionPublisher(backgroundScope, { epoch + testScheduler.currentTime }) { sent += it }
        publisher.observeSession(session())
        for (bad in listOf(fix(epoch - 60_001), fix(epoch + 30_001), fix(epoch).copy(recordedAtIso = "invalid"))) {
            publisher.accept(bad)
            runCurrent()
        }
        assertTrue(sent.isEmpty())
        publisher.accept(fix(epoch))
        runCurrent()
        assertEquals(1, sent.size)
    }
}
