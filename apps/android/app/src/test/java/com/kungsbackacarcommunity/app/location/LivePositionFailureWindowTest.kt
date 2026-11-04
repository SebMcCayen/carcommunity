package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePositionFailureWindowTest {
    private val bad = LiveFixVerdict.REJECT_ACCURACY

    @Test fun shortBurstsAndSparseFailuresStayQuiet() {
        val window = LivePositionFailureWindow()
        repeat(5) { assertFalse(window.onRejected(bad, it * 5_000L)) }
        repeat(10) { assertFalse(window.onRejected(bad, 100_000L + it * 40_000L)) }
    }

    @Test fun continuousFailureCanReportAfterOneMinute() {
        val window = LivePositionFailureWindow()
        repeat(12) { assertFalse(window.onRejected(bad, it * 5_000L)) }
        assertTrue(window.onRejected(bad, 60_000L))
    }

    @Test fun acceptedFixEndsTheEpisode() {
        val window = LivePositionFailureWindow()
        repeat(12) { window.onRejected(bad, it * 5_000L) }
        window.reset()
        repeat(12) { assertFalse(window.onRejected(bad, 60_000L + it * 5_000L)) }
        assertTrue(window.onRejected(bad, 120_000L))
    }

    @Test fun replayedTimestampsCannotKeepAnOldEpisodeAlive() {
        val window = LivePositionFailureWindow()
        repeat(5) { window.onRejected(bad, it * 5_000L) }
        for (now in 25_000L..90_000L step 5_000L) {
            assertFalse(window.onRejected(LiveFixVerdict.REJECT_NOT_NEWER, now))
        }
        assertFalse(window.onRejected(bad, 95_000L))
    }

    @Test fun clockRollbackStartsANewEpisode() {
        val window = LivePositionFailureWindow()
        repeat(12) { window.onRejected(bad, 100_000L + it * 5_000L) }
        assertFalse(window.onRejected(bad, 50_000L))
        assertFalse(window.onRejected(bad, 70_000L))
    }

    @Test fun repeatedOrOlderBadMeasurementsCannotSustainAnEpisode() {
        val window = LivePositionFailureWindow()
        repeat(5) { window.onRejected(bad, it * 5_000L, it * 5_000L) }
        for (now in 25_000L..90_000L step 5_000L) {
            assertFalse(window.onRejected(bad, now, 20_000L))
            assertFalse(window.onRejected(bad, now, 15_000L))
        }
        assertFalse(window.onRejected(bad, 95_000L, 95_000L))
    }

    @Test fun missingTimestampsCannotEstablishFreshFailureEvidence() {
        val window = LivePositionFailureWindow()
        for (now in 0L..120_000L step 5_000L) {
            assertFalse(window.onRejected(bad, now, null))
        }
        assertFalse(window.onRejected(bad, 125_000L, 125_000L))
    }

    @Test fun suppressedEarlyThresholdDoesNotConsumeTheRunReport() {
        val window = LivePositionFailureWindow()
        val log = LivePositionRejectionLog()
        for (now in 0L until 60_000L step 5_000L) {
            assertFalse(log.record(LiveFixRejection(now, LiveFixSource.PUBLISH, bad), window.onRejected(bad, now)))
        }
        assertTrue(log.record(LiveFixRejection(60_000L, LiveFixSource.PUBLISH, bad), window.onRejected(bad, 60_000L)))
        assertFalse(log.record(LiveFixRejection(65_000L, LiveFixSource.PUBLISH, bad), window.onRejected(bad, 65_000L)))
        log.reset()
        window.reset()
        assertFalse(log.record(LiveFixRejection(70_000L, LiveFixSource.PUBLISH, bad), window.onRejected(bad, 70_000L)))
    }
}
