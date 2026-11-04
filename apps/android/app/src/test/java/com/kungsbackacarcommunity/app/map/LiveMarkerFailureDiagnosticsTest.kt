package com.kungsbackacarcommunity.app.map

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMarkerFailureDiagnosticsTest {
    private fun member(uid: String, now: Long, accuracy: Double = 1_200.0) = ConvoyMemberPosition(
        uid = uid,
        latitude = 57.5,
        longitude = 12.0,
        updatedAtMillis = now,
        accuracyMeters = accuracy,
    )

    @Test fun shortSeedFailureStaysLocalButSustainedFailureReportsOnce() {
        val reports = mutableListOf<String>()
        val smoother = LiveMarkerSmoother(onRejectionBurst = { _, code -> reports += code })
        repeat(5) { smoother.onPositions(listOf(member("a", it * 5_000L)), it * 5_000L) }
        assertEquals(0, reports.size)
        for (now in 25_000L..150_000L step 5_000L) smoother.onPositions(listOf(member("a", now)), now)
        assertEquals(listOf("REJECT_ACCURACY"), reports)
    }

    @Test fun healthyFixResetsOnlyItsOwnMemberEpisode() {
        val reports = mutableListOf<String>()
        val smoother = LiveMarkerSmoother(onRejectionBurst = { _, code -> reports += code })
        for (now in 0L..55_000L step 5_000L) {
            smoother.onPositions(listOf(member("a", now), member("b", now, 5.0)), now)
        }
        smoother.onPositions(listOf(member("a", 60_000L), member("b", 60_000L, 5.0)), 60_000L)
        assertEquals(listOf("REJECT_ACCURACY"), reports)
    }

    @Test fun unrelatedMembersCannotAccumulateOneFailureEpisode() {
        var reports = 0
        val smoother = LiveMarkerSmoother(onRejectionBurst = { _, _ -> reports++ })
        repeat(30) { i ->
            val now = i * 5_000L
            smoother.onPositions(listOf(member("member-$i", now)), now)
        }
        assertEquals(0, reports)
    }

    @Test fun removingAMemberClearsItsFailureWindow() {
        var reports = 0
        val smoother = LiveMarkerSmoother(onRejectionBurst = { _, _ -> reports++ })
        for (now in 0L..55_000L step 5_000L) smoother.onPositions(listOf(member("a", now)), now)
        smoother.onPositions(emptyList(), 56_000L)
        for (now in 60_000L..115_000L step 5_000L) smoother.onPositions(listOf(member("a", now)), now)
        assertEquals(0, reports)
    }

    @Test fun repeatedBadSeedCannotTurnRosterRefreshesIntoAFailureEpisode() {
        var reports = 0
        val smoother = LiveMarkerSmoother(onRejectionBurst = { _, _ -> reports++ })
        for (now in 0L..90_000L step 5_000L) {
            smoother.onPositions(listOf(member("a", 0L)), now)
        }
        assertEquals(0, reports)
        // Fresh bad measurements can still establish a new, sustained episode.
        for (now in 95_000L..150_000L step 5_000L) {
            smoother.onPositions(listOf(member("a", now)), now)
        }
        assertEquals(0, reports)
        smoother.onPositions(listOf(member("a", 155_000L)), 155_000L)
        assertEquals(1, reports)
    }
}
