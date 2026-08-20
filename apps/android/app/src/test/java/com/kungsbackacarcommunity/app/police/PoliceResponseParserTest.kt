package com.kungsbackacarcommunity.app.police

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure SDK→model parsing for the `police-*` callable payloads — malformed
 * rows are dropped rather than crashing the map, and the created-pin shape is the
 * same row shape as a listNearby entry.
 */
class PoliceResponseParserTest {
    @Test
    fun `parses a listNearby payload and drops malformed rows`() {
        val payload =
            mapOf(
                "policeReports" to
                    listOf(
                        mapOf(
                            "id" to "p1",
                            "latitude" to 57.5,
                            "longitude" to 12.0,
                            "source" to "manual",
                            "expiresAt" to "2026-08-19T10:40:00.000Z",
                        ),
                        // Missing id → dropped.
                        mapOf("latitude" to 57.5, "longitude" to 12.0),
                        // Missing longitude → dropped.
                        mapOf("id" to "p2", "latitude" to 57.5),
                        "not-a-map",
                    ),
            )
        val result = PoliceResponseParser.parseListNearby(payload)
        assertEquals(listOf("p1"), result.map { it.id })
        assertEquals("manual", result.first().source)
        assertEquals("2026-08-19T10:40:00.000Z", result.first().expiresAtIso)
    }

    @Test
    fun `defaults an absent source to manual`() {
        val payload =
            mapOf(
                "policeReports" to
                    listOf(mapOf("id" to "p1", "latitude" to 57.5, "longitude" to 12.0)),
            )
        assertEquals("manual", PoliceResponseParser.parseListNearby(payload).first().source)
    }

    @Test
    fun `parses the created-report row`() {
        val payload =
            mapOf(
                "id" to "created",
                "latitude" to 57.5,
                "longitude" to 12.0,
                "source" to "convoy",
                "expiresAt" to "2026-08-19T10:40:00.000Z",
            )
        val report = PoliceResponseParser.parseReport(payload)!!
        assertEquals("created", report.id)
        assertEquals("convoy", report.source)
    }

    @Test
    fun `null or empty payloads degrade to empty or null`() {
        assertTrue(PoliceResponseParser.parseListNearby(null).isEmpty())
        assertTrue(PoliceResponseParser.parseListNearby(mapOf("policeReports" to null)).isEmpty())
        assertNull(PoliceResponseParser.parseReport(null))
        assertNull(PoliceResponseParser.parseReport(mapOf("latitude" to 57.5)))
    }

    @Test
    fun `parses verify tallies and defaults absent counts to zero`() {
        val withCounts =
            mapOf(
                "policeReports" to
                    listOf(
                        mapOf(
                            "id" to "p1",
                            "latitude" to 57.5,
                            "longitude" to 12.0,
                            "confirmationCount" to 3.0,
                            "disputeCount" to 1.0,
                        ),
                    ),
            )
        val row = PoliceResponseParser.parseListNearby(withCounts).first()
        assertEquals(3, row.confirmationCount)
        assertEquals(1, row.disputeCount)

        // A legacy payload with no count fields degrades both to 0 (still alerts).
        val noCounts =
            mapOf("policeReports" to listOf(mapOf("id" to "p2", "latitude" to 57.5, "longitude" to 12.0)))
        val legacy = PoliceResponseParser.parseListNearby(noCounts).first()
        assertEquals(0, legacy.confirmationCount)
        assertEquals(0, legacy.disputeCount)
    }

    @Test
    fun `parses a verify result and defaults absent fields`() {
        val payload =
            mapOf(
                "policeReportId" to "p1",
                "confirmationCount" to 2.0,
                "disputeCount" to 0.0,
                "alreadyVoted" to true,
                "switched" to true,
            )
        val result = PoliceResponseParser.parseVerify(payload)!!
        assertEquals("p1", result.policeReportId)
        assertEquals(2, result.confirmationCount)
        assertTrue(result.alreadyVoted)
        assertTrue(result.switched)

        // No id → null (unusable). Absent booleans/counts default.
        assertNull(PoliceResponseParser.parseVerify(mapOf("confirmationCount" to 1.0)))
        val defaults = PoliceResponseParser.parseVerify(mapOf("policeReportId" to "x"))!!
        assertEquals(0, defaults.confirmationCount)
        assertEquals(false, defaults.alreadyVoted)
        assertEquals(false, defaults.switched)
    }
}
