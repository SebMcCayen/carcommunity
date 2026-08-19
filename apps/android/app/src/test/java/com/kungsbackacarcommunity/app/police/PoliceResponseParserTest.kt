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
}
