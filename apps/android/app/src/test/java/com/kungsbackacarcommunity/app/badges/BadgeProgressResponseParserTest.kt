package com.kungsbackacarcommunity.app.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Projection of the `badges-getMyProgress` payload into [BadgeCounters].
 *
 * The wire shape is whatever the Firebase callable SDK decodes a JSON object to
 * — numbers arrive as [Int]/[Long]/[Double]. The parser trusts a counter only
 * when it is a finite, non-negative number, floors a fractional metre to match
 * the server's integer counter, and reads anything else as null (no bar) rather
 * than a fabricated value.
 */
class BadgeProgressResponseParserTest {

    @Test
    fun `a full payload maps one to one onto the seven counters`() {
        val counters =
            BadgeProgressResponseParser.parse(
                mapOf(
                    "crownsCollected" to 34L,
                    "lifetimeDistanceMeters" to 234_567L,
                    "verifiedEventsAttended" to 7L,
                    "bestDayStreak" to 12L,
                    "convoysLed" to 3L,
                    "vehiclesInGarage" to 4L,
                    "seasonsWon" to 1L,
                ),
            )

        assertEquals(34L, counters.crownsCollected)
        assertEquals(234_567L, counters.lifetimeDistanceMeters)
        assertEquals(7L, counters.verifiedEventsAttended)
        assertEquals(12L, counters.bestDayStreak)
        assertEquals(3L, counters.convoysLed)
        assertEquals(4L, counters.vehiclesInGarage)
        assertEquals(1L, counters.seasonsWon)

        // Every ladder therefore observes a value → every ladder can draw a bar.
        for (id in BadgeLadderId.entries) {
            assertEquals("$id must observe its counter", true, counters.observedValue(id) != null)
        }
    }

    @Test
    fun `Int and Double encodings are both accepted, fractions floored`() {
        val counters =
            BadgeProgressResponseParser.parse(
                mapOf(
                    // Firebase may decode a small integer as Int, and a summed
                    // distance as a fractional Double.
                    "crownsCollected" to 5,
                    "lifetimeDistanceMeters" to 234_567.8,
                ),
            )
        assertEquals(5L, counters.crownsCollected)
        // Floored, not rounded — keeps a `>=` threshold test exact.
        assertEquals(234_567L, counters.lifetimeDistanceMeters)
    }

    @Test
    fun `a missing counter is null, not zero`() {
        val counters = BadgeProgressResponseParser.parse(emptyMap())
        assertNull(counters.crownsCollected)
        assertNull(counters.lifetimeDistanceMeters)
        assertNull(counters.verifiedEventsAttended)
        assertNull(counters.bestDayStreak)
        assertNull(counters.convoysLed)
        assertNull(counters.vehiclesInGarage)
        assertNull(counters.seasonsWon)
    }

    @Test
    fun `non-numeric, non-finite and negative values read as null`() {
        val counters =
            BadgeProgressResponseParser.parse(
                mapOf(
                    "crownsCollected" to "12", // a string is not a counter
                    "lifetimeDistanceMeters" to Double.NaN,
                    "verifiedEventsAttended" to Double.POSITIVE_INFINITY,
                    "bestDayStreak" to -4L, // a negative counter is impossible
                    "convoysLed" to null,
                ),
            )
        assertNull(counters.crownsCollected)
        assertNull(counters.lifetimeDistanceMeters)
        assertNull(counters.verifiedEventsAttended)
        assertNull(counters.bestDayStreak)
        assertNull(counters.convoysLed)
    }

    @Test
    fun `zero is a real counter and is kept`() {
        val counters =
            BadgeProgressResponseParser.parse(mapOf("vehiclesInGarage" to 0L))
        assertEquals(0L, counters.vehiclesInGarage)
    }
}
