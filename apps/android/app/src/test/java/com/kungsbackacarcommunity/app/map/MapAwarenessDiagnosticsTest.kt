package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint
import com.kungsbackacarcommunity.app.map.MapAwarenessDiagnostics.ChipProjectionVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure classification and public-safe wording behind the map-awareness field
 * telemetry: which projection faults get named, when a burst escalates, and that
 * the escalated report carries no coordinate or identity.
 */
class MapAwarenessDiagnosticsTest {

    private val width = 1000f
    private val height = 2000f
    private val margin = 60f

    // ---- chip classification -----------------------------------------------

    @Test
    fun `an honest on-screen projection agreeing with its bearing reads on-screen`() {
        val verdict =
            MapAwarenessDiagnostics.classifyChipProjection(
                projected = ProjectedPoint(500f, 300f),
                roundTripTrustworthy = true,
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 0.0,
            )
        assertEquals(ChipProjectionVerdict.ON_SCREEN, verdict)
        assertFalse(MapAwarenessDiagnostics.isFault(verdict))
    }

    @Test
    fun `a point projected far outside reads off-screen and is not a fault`() {
        val verdict =
            MapAwarenessDiagnostics.classifyChipProjection(
                projected = ProjectedPoint(-5000f, 900f),
                roundTripTrustworthy = true,
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 270.0,
            )
        assertEquals(ChipProjectionVerdict.OFF_SCREEN, verdict)
        assertFalse(MapAwarenessDiagnostics.isFault(verdict))
    }

    @Test
    fun `a null or non-finite projection reads non-finite fault`() {
        assertEquals(
            ChipProjectionVerdict.HIDDEN_NONFINITE,
            MapAwarenessDiagnostics.classifyChipProjection(null, false, width, height, margin, 0.0),
        )
        assertEquals(
            ChipProjectionVerdict.HIDDEN_NONFINITE,
            MapAwarenessDiagnostics.classifyChipProjection(
                ProjectedPoint(Float.NaN, 5f), true, width, height, margin, 0.0,
            ),
        )
    }

    @Test
    fun `a pixel clamped to the top-left corner reads as a corner clamp`() {
        // The reported bug: (0,0) with a bearing pointing up-left (315). The ≤90°
        // angle heuristic ACCEPTS it — that is the leak — but the renderer's round
        // trip reports it untrustworthy, so the classifier names it a corner clamp.
        val verdict =
            MapAwarenessDiagnostics.classifyChipProjection(
                projected = ProjectedPoint(0f, 0f),
                roundTripTrustworthy = false,
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 315.0,
            )
        assertEquals(ChipProjectionVerdict.HIDDEN_CORNER_CLAMP, verdict)
        assertTrue(MapAwarenessDiagnostics.isFault(verdict))
    }

    @Test
    fun `a corner clamp that the angle heuristic alone would have drawn is still caught`() {
        // Belt-and-suspenders: even if some renderer could not self-assess and
        // reported trustworthy, a (0,0) pixel whose bearing pointed DOWN-RIGHT (135)
        // is rejected by the angle heuristic and named a corner clamp.
        val verdict =
            MapAwarenessDiagnostics.classifyChipProjection(
                projected = ProjectedPoint(0f, 0f),
                roundTripTrustworthy = true,
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 135.0,
            )
        assertEquals(ChipProjectionVerdict.HIDDEN_CORNER_CLAMP, verdict)
    }

    @Test
    fun `a mirror fold near the centre reads as a generic fold`() {
        // Folded to just above centre while the target is behind us (180): the
        // round trip fails and it is not at a corner, so it is a fold.
        val verdict =
            MapAwarenessDiagnostics.classifyChipProjection(
                projected = ProjectedPoint(500f, 200f),
                roundTripTrustworthy = false,
                viewportWidth = width,
                viewportHeight = height,
                marginPx = margin,
                expectedScreenAngle = 180.0,
            )
        assertEquals(ChipProjectionVerdict.HIDDEN_FOLD, verdict)
    }

    @Test
    fun `every corner is recognised as a clamp`() {
        for (corner in listOf(ProjectedPoint(0f, 0f), ProjectedPoint(width, 0f), ProjectedPoint(0f, height), ProjectedPoint(width, height))) {
            assertTrue(MapAwarenessDiagnostics.isCornerClamp(corner, width, height))
        }
        assertFalse(MapAwarenessDiagnostics.isCornerClamp(ProjectedPoint(500f, 1000f), width, height))
    }

    // ---- the chip escalation log -------------------------------------------

    @Test
    fun `the chip log escalates exactly once when faults cross the threshold`() {
        val log = MapAwarenessDiagnostics.MapAwarenessLog(escalateAfter = 3)
        // On-screen and off-screen frames are not faults and never escalate.
        assertFalse(log.recordChip(ChipProjectionVerdict.ON_SCREEN))
        assertFalse(log.recordChip(ChipProjectionVerdict.OFF_SCREEN))
        // Three faults: the third crosses the threshold, once.
        assertFalse(log.recordChip(ChipProjectionVerdict.HIDDEN_CORNER_CLAMP))
        assertFalse(log.recordChip(ChipProjectionVerdict.HIDDEN_FOLD))
        assertTrue(log.recordChip(ChipProjectionVerdict.HIDDEN_CORNER_CLAMP))
        // And never again this cycle, however many more arrive.
        assertFalse(log.recordChip(ChipProjectionVerdict.HIDDEN_FOLD))
        assertEquals(4, log.faultTotal())
    }

    @Test
    fun `identical corner-clamp verdicts across updates accumulate and escalate once`() {
        // The persistent stuck-chip case the overlay keying fix targets: the SAME
        // HIDDEN_CORNER_CLAMP verdict recorded on every settled frame must keep
        // accumulating toward the threshold (the overlay keys its effect on the
        // camera/roster, not the verdict list, so identical verdicts still
        // re-record), and the report must fire exactly once when it crosses.
        val log = MapAwarenessDiagnostics.MapAwarenessLog(escalateAfter = 4)
        var escalations = 0
        repeat(10) {
            if (log.recordChip(ChipProjectionVerdict.HIDDEN_CORNER_CLAMP)) escalations++
        }
        assertEquals(1, escalations)
        assertEquals(10, log.faultTotal())
    }

    @Test
    fun `resetting the chip log re-arms the escalation`() {
        val log = MapAwarenessDiagnostics.MapAwarenessLog(escalateAfter = 1)
        assertTrue(log.recordChip(ChipProjectionVerdict.HIDDEN_FOLD))
        log.reset()
        assertEquals(0, log.faultTotal())
        assertTrue(log.recordChip(ChipProjectionVerdict.HIDDEN_FOLD))
    }

    // ---- the convoy-fit escalation log -------------------------------------

    @Test
    fun `a fit frame with everyone in view is not a fault`() {
        val frame = MapAwarenessDiagnostics.ConvoyFitFrame(memberCount = 4, offScreenNonStale = 0)
        assertFalse(frame.isFault)
        val log = MapAwarenessDiagnostics.ConvoyFitLog(escalateAfter = 1)
        assertFalse(log.recordFrame(frame))
    }

    @Test
    fun `the fit log escalates once when fresh members keep falling off frame`() {
        val log = MapAwarenessDiagnostics.ConvoyFitLog(escalateAfter = 2)
        val faulty = MapAwarenessDiagnostics.ConvoyFitFrame(memberCount = 5, offScreenNonStale = 2)
        assertFalse(log.recordFrame(faulty))
        assertTrue(log.recordFrame(faulty))
        assertFalse(log.recordFrame(faulty))
        val summary = log.summary()
        assertEquals(3, summary.faultyFits)
        assertEquals(2, summary.worstOffScreenNonStale)
        assertEquals(5, summary.largestMemberCount)
    }

    @Test
    fun `identical faulty fit frames across updates accumulate and escalate once`() {
        // The convoy-fit analogue: consecutive settled frames with the same
        // member/off-screen counts (a member stuck off-frame while the fit holds
        // steady) must count as a RUN and escalate once, which is why the overlay
        // keys its effect on the settle inputs rather than the data-class frame.
        val log = MapAwarenessDiagnostics.ConvoyFitLog(escalateAfter = 3)
        val frame = MapAwarenessDiagnostics.ConvoyFitFrame(memberCount = 4, offScreenNonStale = 1)
        var escalations = 0
        repeat(8) { if (log.recordFrame(frame)) escalations++ }
        assertEquals(1, escalations)
        assertEquals(8, log.summary().faultyFits)
    }

    // ---- public-safe report wording ----------------------------------------

    @Test
    fun `the chip report names the dominant fault and carries no coordinate`() {
        val counts =
            mapOf(
                ChipProjectionVerdict.ON_SCREEN to 10,
                ChipProjectionVerdict.HIDDEN_CORNER_CLAMP to 5,
                ChipProjectionVerdict.HIDDEN_FOLD to 2,
            )
        assertEquals("HIDDEN_CORNER_CLAMP", MapAwarenessReport.chipCode(counts))
        val message = MapAwarenessReport.chipMessage(7, counts, width, height)
        assertTrue(message.contains("HIDDEN_CORNER_CLAMP=5"))
        assertTrue(message.contains("medium")) // 1000x2000 → min side 1000 → medium
        // No decimal coordinates leaked.
        assertFalse(message.contains("57."))
        assertFalse(message.contains("12."))
    }

    @Test
    fun `the fit report buckets the group size and states the worst off-screen count`() {
        val summary =
            MapAwarenessDiagnostics.ConvoyFitSummary(
                faultyFits = 4,
                worstOffScreenNonStale = 3,
                largestMemberCount = 6,
            )
        assertEquals("OFFSCREEN_MEMBER", MapAwarenessReport.fitCode(summary))
        val message = MapAwarenessReport.fitMessage(summary)
        assertTrue(message.contains("4 fits"))
        assertTrue(message.contains("worst 3"))
        assertTrue(message.contains("4-8")) // member bucket
    }

    @Test
    fun `size and member buckets fold degenerate inputs into unknown`() {
        assertEquals("unknown", MapAwarenessReport.sizeBucket(0f, 100f))
        assertEquals("unknown", MapAwarenessReport.sizeBucket(Float.NaN, 100f))
        assertEquals("unknown", MapAwarenessReport.memberBucket(0))
    }

    @Test
    fun `a convoy of exactly one other member is its own bucket, not folded into 2-3`() {
        assertEquals("1", MapAwarenessReport.memberBucket(1))
        assertEquals("2-3", MapAwarenessReport.memberBucket(2))
        assertEquals("2-3", MapAwarenessReport.memberBucket(3))
        assertEquals("4-8", MapAwarenessReport.memberBucket(4))
    }

    @Test
    fun `a tie for the dominant fault resolves deterministically by verdict name`() {
        // Equal counts must not let map iteration order pick the dedup code, or one
        // fault would split into several GitHub issues. The tie-break is by name, so
        // both orderings yield the same code regardless of insertion order.
        val counts =
            mapOf(
                ChipProjectionVerdict.HIDDEN_NONFINITE to 4,
                ChipProjectionVerdict.HIDDEN_FOLD to 4,
            )
        val reversed =
            mapOf(
                ChipProjectionVerdict.HIDDEN_FOLD to 4,
                ChipProjectionVerdict.HIDDEN_NONFINITE to 4,
            )
        assertEquals("HIDDEN_NONFINITE", MapAwarenessReport.chipCode(counts))
        assertEquals(MapAwarenessReport.chipCode(counts), MapAwarenessReport.chipCode(reversed))
    }
}
