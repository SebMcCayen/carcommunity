package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.shell.MapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the convoy FOLLOW-ME leader-trail pure logic
 * ([FollowMeTrail], [FollowMeTrailPublisher]). No device, no Firebase, no map.
 *
 * Uses `java.util.Base64` + [com.kungsbackacarcommunity.app.drives.RouteCodec] —
 * both plain-JVM — so the encode/decode round-trip and the 15 km window run off
 * device, and the encoding is byte-compatible with the server's route-codec.ts.
 */
class FollowMeTrailTest {

    @Test
    fun `encode then decode round-trips the trail geometry`() {
        val points =
            listOf(
                MapPoint(longitude = 12.0000, latitude = 57.0000),
                MapPoint(longitude = 12.0010, latitude = 57.0005),
                MapPoint(longitude = 12.0025, latitude = 57.0012),
            )
        val decoded = FollowMeTrail.decode(FollowMeTrail.encode(points))
        assertEquals(points.size, decoded.size)
        for (i in points.indices) {
            // 1e-5 fixed-point precision (~1.1 m), the codec's documented scale.
            assertEquals(points[i].latitude, decoded[i].latitude, 1e-5)
            assertEquals(points[i].longitude, decoded[i].longitude, 1e-5)
        }
    }

    @Test
    fun `decode of blank or corrupt input is empty, never throws`() {
        assertTrue(FollowMeTrail.decode(null).isEmpty())
        assertTrue(FollowMeTrail.decode("").isEmpty())
        assertTrue(FollowMeTrail.decode("!!!not base64!!!").isEmpty())
        assertTrue(FollowMeTrail.decode("Zm9vYmFy").isEmpty()) // valid base64, not CCRB
    }

    @Test
    fun `encode of an empty trail is the empty string`() {
        assertEquals("", FollowMeTrail.encode(emptyList()))
    }

    @Test
    fun `isSelfLeading is true only when the leader is the local user`() {
        assertTrue(FollowMeTrail.isSelfLeading("me", "me"))
        assertFalse(FollowMeTrail.isSelfLeading("bob", "me"))
        assertFalse(FollowMeTrail.isSelfLeading(null, "me"))
        assertFalse(FollowMeTrail.isSelfLeading("me", null))
    }

    @Test
    fun `shouldDraw gates on leader, self, membership and freshness`() {
        val now = 1_000_000L
        val fresh = now - 1_000L
        // A fresh trail led by another member draws.
        assertTrue(FollowMeTrail.shouldDraw("bob", "me", true, fresh, now))
        // No leader -> nothing.
        assertFalse(FollowMeTrail.shouldDraw(null, "me", true, fresh, now))
        // The viewer's own trail is not drawn as the shared line.
        assertFalse(FollowMeTrail.shouldDraw("me", "me", true, fresh, now))
        // Leader no longer a member -> nothing.
        assertFalse(FollowMeTrail.shouldDraw("bob", "me", false, fresh, now))
        // Stale (vanished leader) -> nothing.
        assertFalse(FollowMeTrail.shouldDraw("bob", "me", true, now - FollowMeTrail.STALE_MS, now))
    }

    @Test
    fun `isFresh fails closed on a null signal and honours the window`() {
        val now = 1_000_000L
        assertFalse(FollowMeTrail.isFresh(null, now))
        assertTrue(FollowMeTrail.isFresh(now - (FollowMeTrail.STALE_MS - 1), now))
        assertFalse(FollowMeTrail.isFresh(now - FollowMeTrail.STALE_MS, now))
    }

    @Test
    fun `the trail buffer rolls at ~15 km, far longer than the 1 km self-trail`() {
        assertEquals(15_000.0, FollowMeTrail.TRAIL_WINDOW_METERS, 0.0)
        val publisher = FollowMeTrailPublisher(throttleMs = 0L)
        // Walk ~25 km north in ~200 m steps (0.0018 deg lat ~= 200 m).
        var lat = 57.0
        var t = 0L
        repeat(130) {
            lat += 0.0018
            t += 5_000
            publisher.onFix(MapPoint(longitude = 12.0, latitude = lat), t)
        }
        val pts = publisher.points()
        // Trimmed to about the 15 km window: comfortably under the ~25 km driven,
        // and still at least the window (the trail keeps >= windowMeters on screen).
        val length = trailLength(pts)
        assertTrue("length was $length", length in 15_000.0..15_600.0)
    }

    @Test
    fun `the publisher throttles writes and only flushes when the trail changed`() {
        val publisher = FollowMeTrailPublisher(throttleMs = 4_000L)
        // First real fix at t=0 flushes immediately (dirty + throttle window elapsed
        // from Long.MIN_VALUE).
        val first = publisher.onFix(MapPoint(12.0, 57.0), 0L)
        assertNotNull(first)
        // A moved fix 1s later is inside the throttle window -> no write yet.
        assertNull(publisher.onFix(MapPoint(12.002, 57.0), 1_000L))
        // Past the window with a change since the last flush -> a write.
        assertNotNull(publisher.onFix(MapPoint(12.004, 57.0), 5_000L))
        // A jitter fix (< 5 m from the last point 12.004) does not dirty the
        // buffer -> no write even past the throttle window.
        assertNull(publisher.onFix(MapPoint(12.0040001, 57.0), 20_000L))
    }

    @Test
    fun `reset clears the buffer and the throttle`() {
        val publisher = FollowMeTrailPublisher(throttleMs = 4_000L)
        publisher.onFix(MapPoint(12.0, 57.0), 0L)
        publisher.onFix(MapPoint(12.002, 57.0), 5_000L)
        assertTrue(publisher.points().isNotEmpty())
        publisher.reset()
        assertTrue(publisher.points().isEmpty())
        // After reset the next fix flushes immediately again.
        assertNotNull(publisher.onFix(MapPoint(12.0, 57.0), 6_000L))
    }

    private fun trailLength(points: List<MapPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum +=
                com.kungsbackacarcommunity.app.shell.BreadcrumbTrail.haversineMeters(
                    points[i - 1],
                    points[i],
                )
        }
        return sum
    }
}
