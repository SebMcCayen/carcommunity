package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics half of the live-position fix: Seb asked for a log so a
 * recurrence can be understood rather than merely re-reported.
 *
 * What is provable off-device is that the log stays BOUNDED while driving, that
 * it escalates exactly once per run rather than per bad fix, and that what it
 * would send is coordinate-free and coarse.
 */
class LivePositionDiagnosticsTest {

    private fun rejection(
        verdict: LiveFixVerdict = LiveFixVerdict.REJECT_ACCURACY,
        accuracyMeters: Double? = null,
        distanceMeters: Double? = null,
        mockProvider: Boolean? = null,
    ) = LiveFixRejection(
        atMillis = 1_700_000_000_000L,
        source = LiveFixSource.PUBLISH,
        verdict = verdict,
        accuracyMeters = accuracyMeters,
        distanceMeters = distanceMeters,
        mockProvider = mockProvider,
    )

    /**
     * The buffer runs for the length of a drive. It must have a ceiling, and the
     * ceiling must evict the OLDEST entry — a post-mortem wants the tail.
     */
    @Test
    fun theLogIsBoundedAndKeepsTheMostRecentEntries() {
        val log = LivePositionRejectionLog(capacity = 3)
        repeat(10) { i -> log.record(rejection(distanceMeters = i.toDouble())) }

        val kept = log.snapshot()
        assertEquals(3, kept.size)
        assertEquals(listOf(7.0, 8.0, 9.0), kept.map { it.distanceMeters })
        assertEquals("every rejection is still counted", 10, log.totalRejections())
    }

    /**
     * One bad fix is weather; a burst is a fault. The escalation must fire on the
     * threshold and then never again, or a bad-GPS drive would spend the user's
     * whole 30/hour server budget on one problem.
     */
    @Test
    fun escalationFiresExactlyOncePerRun() {
        val log = LivePositionRejectionLog()
        val fired = (1..20).map { log.record(rejection()) }

        assertEquals(
            "fires on the Nth rejection",
            LivePositionRejectionLog.ESCALATE_AFTER_REJECTIONS,
            fired.indexOfFirst { it } + 1,
        )
        assertEquals("and only once", 1, fired.count { it })
    }

    /** A reset (a fresh sharing session) re-arms it. */
    @Test
    fun resetClearsTheBufferAndRearmsEscalation() {
        val log = LivePositionRejectionLog()
        repeat(LivePositionRejectionLog.ESCALATE_AFTER_REJECTIONS) { log.record(rejection()) }
        log.reset()

        assertTrue(log.snapshot().isEmpty())
        assertEquals(0, log.totalRejections())
        val fired = (1..LivePositionRejectionLog.ESCALATE_AFTER_REJECTIONS).map { log.record(rejection()) }
        assertEquals("a new run can escalate again", 1, fired.count { it })
    }

    /** The summary is what the one report is built from. */
    @Test
    fun theSummaryAggregatesTheRetainedEntries() {
        val log = LivePositionRejectionLog()
        log.record(rejection(accuracyMeters = 80.0, distanceMeters = 300.0))
        log.record(rejection(accuracyMeters = 1_400.0, distanceMeters = 1_800.0))
        log.record(rejection(verdict = LiveFixVerdict.REJECT_SPEED, mockProvider = true))

        val summary = log.summary()
        assertEquals(3, summary.total)
        assertEquals(2, summary.byVerdict[LiveFixVerdict.REJECT_ACCURACY])
        assertEquals(1, summary.byVerdict[LiveFixVerdict.REJECT_SPEED])
        assertEquals(1_400.0, summary.worstAccuracyMeters!!, 0.0)
        assertEquals(1_800.0, summary.largestJumpMeters!!, 0.0)
        assertTrue(summary.mockProviderSeen)
        assertEquals(LiveFixVerdict.REJECT_ACCURACY, LivePositionRejectionReport.dominantVerdict(summary))
    }

    /**
     * The escalated report becomes a WORLD-READABLE GitHub issue, so its numbers
     * are bands, not measurements — and no coordinate may appear in it under any
     * circumstances.
     */
    @Test
    fun theEscalatedMessageIsBucketedAndCarriesNoCoordinates() {
        val log = LivePositionRejectionLog()
        log.record(rejection(accuracyMeters = 1_437.0, distanceMeters = 1_812.0))
        val message = LivePositionRejectionReport.message(LiveFixSource.PUBLISH, log.summary())

        assertTrue("says how many", message.contains("1 fix not used"))
        assertTrue("bands the accuracy", message.contains("1000+ m"))
        assertFalse("never the exact figure", message.contains("1437"))
        assertFalse("nor the exact distance", message.contains("1812"))
        // Nothing that could be a latitude or longitude in Kungsbacka.
        assertFalse(message.contains("57."))
        assertFalse(message.contains("12."))
    }

    /**
     * The line is the body of a public GitHub issue, so it has to read like
     * English. The singular is reachable rather than theoretical — the
     * escalation threshold is a tunable constant.
     */
    @Test
    fun theSummaryAgreesInNumber() {
        val one = LivePositionRejectionLog()
        one.record(rejection())
        val singular = LivePositionRejectionReport.message(LiveFixSource.PUBLISH, one.summary())
        assertTrue(singular.contains("1 fix not used"))
        assertFalse("never \"1 fixes\"", singular.contains("1 fixes"))

        val many = LivePositionRejectionLog()
        repeat(3) { many.record(rejection()) }
        assertTrue(
            LivePositionRejectionReport.message(LiveFixSource.PUBLISH, many.summary())
                .contains("3 fixes not used"),
        )
    }

    /** Every band, including the "the platform did not say" one. */
    @Test
    fun metreBandsCoverTheWholeRange() {
        assertEquals("unknown", LivePositionRejectionReport.bucketMeters(null))
        assertEquals("unknown", LivePositionRejectionReport.bucketMeters(Double.NaN))
        assertEquals("<50 m", LivePositionRejectionReport.bucketMeters(12.0))
        assertEquals("50-199 m", LivePositionRejectionReport.bucketMeters(50.0))
        assertEquals("200-999 m", LivePositionRejectionReport.bucketMeters(999.0))
        assertEquals("1000+ m", LivePositionRejectionReport.bucketMeters(1_000.0))
    }

    /**
     * The band LABELS must name the band's real contents. Values arrive already
     * rounded to whole metres, so every boundary has to land in the band whose
     * label includes it — a "50-200 m" label over a band that excludes 200 is
     * how someone misreads a bug report an hour into debugging it.
     */
    @Test
    fun bandLabelsAreHonestAtEveryBoundary() {
        assertEquals("<50 m", LivePositionRejectionReport.bucketMeters(49.0))
        assertEquals("50-199 m", LivePositionRejectionReport.bucketMeters(199.0))
        assertEquals("200-999 m", LivePositionRejectionReport.bucketMeters(200.0))
        assertEquals("1000+ m", LivePositionRejectionReport.bucketMeters(1_000.0))
        assertEquals("<50 m", LivePositionRejectionReport.bucketMeters(0.0))
    }

    /**
     * A NEGATIVE accuracy is not a measurement, and must read as "unknown" — the
     * same fold [LivePositionQuality.normalizedAccuracy] applies. Bucketing it
     * as "<50 m" would report a nonsense fix as an excellent one, in the very
     * line whose job is to explain why a fix was bad.
     */
    @Test
    fun aNegativeMagnitudeIsUnknownNotExcellent() {
        assertEquals("unknown", LivePositionRejectionReport.bucketMeters(-1.0))
        assertEquals("unknown", LivePositionRejectionReport.bucketMeters(-9_999.0))
        assertNull(
            "and the quality rules agree it is unknown",
            LivePositionQuality.normalizedAccuracy(-1.0),
        )
    }

    /**
     * The wording must not overclaim. HOLD_UNCORROBORATED is a DELAYED
     * ACCEPTANCE — that sample was not drawn, but the position may have been
     * adopted a second later — so the summary says "not used", never
     * "discarded", and shows the per-rule mix so a reader can see what actually
     * happened.
     */
    @Test
    fun theSummaryDoesNotCallAHeldFixDiscarded() {
        val log = LivePositionRejectionLog()
        log.record(rejection(verdict = LiveFixVerdict.HOLD_UNCORROBORATED, distanceMeters = 1_800.0))
        val message = LivePositionRejectionReport.message(LiveFixSource.RENDER, log.summary())

        assertFalse("a held fix was not discarded", message.contains("discarded"))
        assertTrue(message.contains("1 fix not used"))
        assertTrue("and the reason is spelled out", message.contains("HOLD_UNCORROBORATED=1"))
    }

    /**
     * The dedup code is the dominant reason, so recurrences of one fault bump a
     * single issue instead of filing one per burst. An empty log still yields a
     * usable code rather than crashing the report path.
     */
    @Test
    fun theDedupCodeIsStableAndSafeWhenEmpty() {
        val log = LivePositionRejectionLog()
        assertEquals("UNKNOWN", LivePositionRejectionReport.code(log.summary()))
        log.record(rejection(verdict = LiveFixVerdict.REJECT_SPEED))
        log.record(rejection(verdict = LiveFixVerdict.REJECT_SPEED))
        log.record(rejection(verdict = LiveFixVerdict.REJECT_ACCURACY))
        assertEquals("REJECT_SPEED", LivePositionRejectionReport.code(log.summary()))
    }

    /** Rounding keeps the local buffer in whole units and drops nonsense. */
    @Test
    fun roundingIsWholeUnitsAndNullSafe() {
        assertEquals(9.0, LivePositionRejectionReport.round(8.6)!!, 0.0)
        assertEquals(null, LivePositionRejectionReport.round(null))
        assertEquals(null, LivePositionRejectionReport.round(Double.NaN))
        assertEquals(null, LivePositionRejectionReport.round(Double.POSITIVE_INFINITY))
    }
}
