package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewerStreamsTest {
    @Test fun `one pending uid cannot block another marker and removed values clear independently`() = runTest {
        val marker = LiveMarker("ready", 0.0, 0.0)
        val ready = MutableStateFlow<LiveMarker?>(marker)
        val frames = mutableListOf<List<LiveMarker?>>()
        backgroundScope.launch {
            observeLiveMarkerRoster(listOf("ready", "pending")) { uid ->
                if (uid == "ready") ready else flow { delay(Long.MAX_VALUE) }
            }.collect { frames += it }
        }
        runCurrent()
        assertEquals(listOf(marker, null), frames.last())
        ready.value = null
        runCurrent()
        assertTrue(frames.last().all { it == null })
    }

    @Test fun `empty roster opens no subscriptions and a null read creates no discovery fallback marker`() = runTest {
        var reads = 0
        val emptyFrames = mutableListOf<List<LiveMarker?>>()
        backgroundScope.launch {
            observeLiveMarkerRoster(emptyList()) { reads++; flowOf(null) }.collect { emptyFrames += it }
        }
        runCurrent()
        assertEquals(0, reads)
        assertEquals(listOf(emptyList<LiveMarker?>()), emptyFrames)
        val deniedFrames = mutableListOf<List<LiveMarker?>>()
        backgroundScope.launch {
            observeLiveMarkerRoster(listOf("denied")) { flowOf(null) }.collect { deniedFrames += it }
        }
        runCurrent()
        assertFalse(deniedFrames.isEmpty())
        assertTrue(deniedFrames.all { frame -> frame.all { it == null } })
    }

    @Test fun `initial null camera waits for readiness without a polling penalty or pan triggered calls`() = runTest {
        val camera = MutableStateFlow<String?>(null)
        val sampled = mutableListOf<Pair<Long, String>>()
        backgroundScope.launch {
            nearbyLivePollSnapshots(camera, 20_000).collect { sampled += testScheduler.currentTime to it }
        }
        runCurrent()
        advanceTimeBy(100)
        camera.value = "first"
        runCurrent()
        assertEquals(listOf(100L to "first"), sampled)
        repeat(20) {
            camera.value = "pan-$it"
            runCurrent()
        }
        assertEquals(1, sampled.size)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(20_100L to "pan-19", sampled.last())
    }

    @Test fun `poll cadence still starts after refresh completes and cancellation ends polling`() = runTest {
        val camera = MutableStateFlow<String?>("ready")
        val times = mutableListOf<Long>()
        val job = backgroundScope.launch {
            nearbyLivePollSnapshots(camera, 20_000).collect {
                times += testScheduler.currentTime
                delay(5_000)
            }
        }
        runCurrent()
        advanceTimeBy(24_999)
        runCurrent()
        assertEquals(listOf(0L), times)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 25_000L), times)
        job.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, times.size)
    }
}
