package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePositionDuplicateDiagnosticsTest {
    private fun fix(verdict: LiveFixVerdict) = LiveFixRejection(
        atMillis = 1_000L,
        source = LiveFixSource.RENDER,
        verdict = verdict,
    )

    @Test fun duplicatesStayLocalAndNeverEscalate() {
        val log = LivePositionRejectionLog()
        repeat(100) { assertFalse(log.record(fix(LiveFixVerdict.REJECT_NOT_NEWER))) }
        assertEquals(100, log.totalRejections())
        assertEquals(32, log.snapshot().size)
        assertEquals(0, log.reportSummary().total)
        assertTrue(log.reportSummary().byVerdict.isEmpty())
    }

    @Test fun duplicatesDoNotConsumeTheFaultThresholdOrBecomeItsDominantCode() {
        val log = LivePositionRejectionLog()
        repeat(4) {
            assertFalse(log.record(fix(LiveFixVerdict.REJECT_ACCURACY)))
            repeat(4) { assertFalse(log.record(fix(LiveFixVerdict.REJECT_NOT_NEWER))) }
        }
        assertTrue(log.record(fix(LiveFixVerdict.REJECT_ACCURACY)))
        assertEquals(21, log.summary().total)
        val report = log.reportSummary()
        assertEquals(5, report.total)
        assertEquals(mapOf(LiveFixVerdict.REJECT_ACCURACY to 5), report.byVerdict)
        assertEquals("REJECT_ACCURACY", LivePositionRejectionReport.code(report))
        assertFalse(LivePositionRejectionReport.message(LiveFixSource.RENDER, report).contains("NOT_NEWER"))
        assertFalse(log.record(fix(LiveFixVerdict.REJECT_SPEED)))
    }

    @Test fun resetRearmsOnlyRealFaults() {
        val log = LivePositionRejectionLog()
        repeat(5) { log.record(fix(LiveFixVerdict.REJECT_SPEED)) }
        log.reset()
        repeat(5) { assertFalse(log.record(fix(LiveFixVerdict.REJECT_NOT_NEWER))) }
        assertEquals(0, log.reportSummary().total)
        repeat(4) { assertFalse(log.record(fix(LiveFixVerdict.REJECT_SPEED))) }
        assertTrue(log.record(fix(LiveFixVerdict.REJECT_SPEED)))
    }
}
