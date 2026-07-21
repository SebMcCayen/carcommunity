package com.kungsbackacarcommunity.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLiveParserTest {

    @Test
    fun `parses a well-formed sessions payload`() {
        val data =
            mapOf(
                "sessions" to
                    listOf(
                        mapOf(
                            "uid" to "u1",
                            "latitude" to 59.334,
                            "longitude" to 18.063,
                            "displayName" to "Sebbe",
                        ),
                    ),
            )
        val parsed = NearbyLiveParser.parse(data)
        assertEquals(1, parsed.size)
        assertEquals(NearbyLiveSession("u1", 59.334, 18.063, "Sebbe"), parsed.first())
    }

    @Test
    fun `numbers arriving as Int or Long are coerced to Double`() {
        val data =
            mapOf(
                "sessions" to
                    listOf(mapOf("uid" to "u1", "latitude" to 59, "longitude" to 18L)),
            )
        val parsed = NearbyLiveParser.parse(data)
        assertEquals(59.0, parsed.single().latitude, 0.0)
        assertEquals(18.0, parsed.single().longitude, 0.0)
        assertEquals(null, parsed.single().displayName)
    }

    @Test
    fun `drops malformed rows but keeps the good ones`() {
        val data =
            mapOf(
                "sessions" to
                    listOf(
                        mapOf("uid" to "good", "latitude" to 1.0, "longitude" to 2.0),
                        mapOf("latitude" to 1.0, "longitude" to 2.0), // no uid
                        mapOf("uid" to "no-coords"), // no lat/lng
                        "not-a-map",
                    ),
            )
        val parsed = NearbyLiveParser.parse(data)
        assertEquals(listOf("good"), parsed.map { it.uid })
    }

    @Test
    fun `null or shapeless payload is an empty list, not a crash`() {
        assertTrue(NearbyLiveParser.parse(null).isEmpty())
        assertTrue(NearbyLiveParser.parse("nonsense").isEmpty())
        assertTrue(NearbyLiveParser.parse(mapOf("sessions" to "not-a-list")).isEmpty())
        assertTrue(NearbyLiveParser.parse(emptyMap<String, Any>()).isEmpty())
    }
}
