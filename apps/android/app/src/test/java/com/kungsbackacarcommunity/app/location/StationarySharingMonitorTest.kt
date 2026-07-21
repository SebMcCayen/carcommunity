package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stationary-sharing cost/safety state machine as pure logic: parked
 * detection, the 10-min prompt, the 5-min auto-stop, and every reset (movement
 * or an affirmative answer). A real device is needed to observe the fused-
 * location callback that drives it; this covers the decisions it makes.
 */
class StationarySharingMonitorTest {

    // A fixed reference point (Kungsbacka) and a point ~111 m north of it — well
    // beyond the 15 m movement threshold — for "moved" fixes.
    private val baseLat = 57.4874
    private val baseLon = 12.0761
    private val movedLat = baseLat + 0.001 // ~111 m north
    private val now = 1_000_000L

    private val prompt = StationarySharingMonitor.STATIONARY_PROMPT_MS
    private val autoStop = StationarySharingMonitor.STATIONARY_AUTOSTOP_MS

    @Test
    fun `timeframes are the Seb-approved 10 and 5 minutes`() {
        assertEquals(10 * 60 * 1000L, StationarySharingMonitor.STATIONARY_PROMPT_MS)
        assertEquals(5 * 60 * 1000L, StationarySharingMonitor.STATIONARY_AUTOSTOP_MS)
    }

    @Test
    fun `a freshly anchored monitor does nothing before the prompt window`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        assertEquals(StationaryDecision.None, monitor.decide(now))
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt - 1))
        assertFalse(monitor.isPromptOutstanding())
    }

    @Test
    fun `parked past 10 minutes prompts exactly once`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        // Small GPS jitter within the threshold keeps it parked.
        monitor.onFix(baseLat + 0.00001, baseLon, now + 60_000L)

        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt))
        assertTrue(monitor.isPromptOutstanding())
        // Re-evaluating inside the grace window does not re-prompt.
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt + 1_000L))
    }

    @Test
    fun `an unanswered prompt auto-stops after the grace window`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt))

        // Just before the grace window closes: still nothing.
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt + autoStop - 1))
        // Grace window elapsed with no answer → stop.
        assertEquals(StationaryDecision.AutoStop, monitor.decide(now + prompt + autoStop))
    }

    @Test
    fun `movement before the prompt resets the stationary clock`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        // Almost at the prompt line, then the car moves: clock restarts here.
        monitor.onFix(movedLat, baseLon, now + prompt - 1_000L)

        // The old deadline no longer fires...
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt))
        // ...but a fresh 10 min from the move does.
        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt - 1_000L + prompt))
    }

    @Test
    fun `moving again after the prompt cancels the pending auto-stop`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt))
        assertTrue(monitor.isPromptOutstanding())

        // The driver pulls away inside the grace window: re-anchor + clear.
        monitor.onFix(movedLat, baseLon, now + prompt + 60_000L)
        assertFalse(monitor.isPromptOutstanding())
        // No auto-stop at what would have been the old deadline.
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt + autoStop))
    }

    @Test
    fun `answering the prompt cancels auto-stop and starts a fresh quiet window`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt))

        val answeredAt = now + prompt + 60_000L
        monitor.answerStillSharing(answeredAt)
        assertFalse(monitor.isPromptOutstanding())

        // What would have been the auto-stop time passes with no stop.
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt + autoStop))
        // Staying put another full window prompts again (not immediately).
        assertEquals(StationaryDecision.None, monitor.decide(answeredAt + prompt - 1))
        assertEquals(StationaryDecision.Prompt, monitor.decide(answeredAt + prompt))
    }

    @Test
    fun `reset clears a pending prompt and the parked clock`() {
        val monitor = StationarySharingMonitor()
        monitor.onFix(baseLat, baseLon, now)
        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt))
        assertTrue(monitor.isPromptOutstanding())

        // Instance reused for a fresh session: no bleed-through.
        monitor.reset()
        assertFalse(monitor.isPromptOutstanding())
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt))
        // A brand-new parked stretch must run its own full 10 min.
        monitor.onFix(baseLat, baseLon, now + prompt)
        assertEquals(StationaryDecision.None, monitor.decide(now + prompt + prompt - 1))
        assertEquals(StationaryDecision.Prompt, monitor.decide(now + prompt + prompt))
    }

    @Test
    fun `custom thresholds are honoured`() {
        val monitor =
            StationarySharingMonitor(
                promptAfterMillis = 100L,
                autoStopAfterPromptMillis = 50L,
            )
        monitor.onFix(baseLat, baseLon, 0L)
        assertEquals(StationaryDecision.Prompt, monitor.decide(100L))
        assertEquals(StationaryDecision.AutoStop, monitor.decide(150L))
    }
}
