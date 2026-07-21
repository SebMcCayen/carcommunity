package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.media.MediaUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the WRITE side of the route file: [RouteUploadRunner] encodes recorded
 * fixes and uploads `route.bin`, and the bytes it writes round-trip back through
 * [RouteCodec] (the reader, #509) to the same track. The retry state machine
 * (success, failure+retry, exhaustion, cancellation) is exercised with a fake
 * uploader and no real delays.
 */
class RouteUploadTest {

    // A realistic short recorded track: a handful of ~1 Hz fixes creeping along
    // a road near Kungsbacka, the metre-scale deltas the codec is tuned for.
    private val track: List<RecordedPoint> =
        (0 until 12).map { i ->
            RecordedPoint(
                latitude = 57.48700 + i * 0.00013,
                longitude = 12.07600 + i * 0.00009,
                timestampMs = 1_700_000_000_000L + i * 1_000L,
            )
        }

    private companion object {
        const val PATH = "rideRoutes/uid-1/ride-1/route.bin"
    }

    // ---- round trip: what we upload is what the reader decodes ---------------

    @Test
    fun `uploaded bytes decode back to the recorded track`() = runTest {
        val uploader = RecordingUploader()
        val runner = RouteUploadRunner(uploader, delayFn = {})

        val outcome = runner.upload(PATH, track)

        assertEquals(RouteUploadOutcome.Uploaded, outcome)
        assertEquals(PATH, uploader.lastPath)
        // gzip content-type, matching what storage.rules permits + the decoder detects.
        assertEquals(RouteUploadRunner.CONTENT_TYPE_GZIP, uploader.lastContentType)

        val decoded = RouteCodec.decode(uploader.lastBytes)
        assertNotNull("uploaded bytes must decode", decoded)
        assertEquals(track.size, decoded!!.size)
        decoded.forEachIndexed { i, point ->
            // COORD_SCALE precision (1e-5°, ~1.1 m) — well under any GPS noise.
            assertEquals(track[i].latitude, point.latitude, 1e-5)
            assertEquals(track[i].longitude, point.longitude, 1e-5)
            assertEquals(track[i].timestampMs, point.timestampMs)
        }
    }

    @Test
    fun `payload is gzipped so the reader auto-detects it`() = runTest {
        val uploader = RecordingUploader()
        RouteUploadRunner(uploader, delayFn = {}).upload(PATH, track)

        val bytes = uploader.lastBytes!!
        // gzip magic: 0x1f 0x8b — the same bytes RouteCodec.decode sniffs for.
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])
    }

    // ---- state machine ------------------------------------------------------

    @Test
    fun `no points is skipped with nothing uploaded`() = runTest {
        val uploader = RecordingUploader()
        val outcome = RouteUploadRunner(uploader, delayFn = {}).upload(PATH, emptyList())

        assertEquals(RouteUploadOutcome.Skipped, outcome)
        assertEquals(0, uploader.attempts)
    }

    @Test
    fun `success on the first attempt does not retry`() = runTest {
        val uploader = RecordingUploader()
        val outcome = RouteUploadRunner(uploader, delayFn = {}).upload(PATH, track)

        assertEquals(RouteUploadOutcome.Uploaded, outcome)
        assertEquals(1, uploader.attempts)
    }

    @Test
    fun `a transient failure retries and then succeeds`() = runTest {
        // Fail the first attempt, succeed on the second.
        val uploader = RecordingUploader(failFirst = 1)
        val delays = mutableListOf<Long>()
        val runner = RouteUploadRunner(uploader, delayFn = { delays.add(it) })

        val outcome = runner.upload(PATH, track)

        assertEquals(RouteUploadOutcome.Uploaded, outcome)
        assertEquals(2, uploader.attempts)
        // Exactly one backoff, between the failed attempt and the retry.
        assertEquals(1, delays.size)
        assertTrue("backoff must be positive", delays.single() > 0)
    }

    @Test
    fun `exhausting all attempts fails with the last cause`() = runTest {
        val boom = IllegalStateException("network down")
        val uploader = RecordingUploader(failFirst = Int.MAX_VALUE, error = boom)
        val delays = mutableListOf<Long>()

        val outcome =
            RouteUploadRunner(uploader, delayFn = { delays.add(it) }).upload(PATH, track)

        val failed = outcome as RouteUploadOutcome.Failed
        assertEquals(RouteUploadRunner.DEFAULT_MAX_ATTEMPTS, failed.attempts)
        assertEquals(RouteUploadRunner.DEFAULT_MAX_ATTEMPTS, uploader.attempts)
        assertSame(boom, failed.cause)
        // A backoff BETWEEN attempts, but none after the final one.
        assertEquals(RouteUploadRunner.DEFAULT_MAX_ATTEMPTS - 1, delays.size)
    }

    @Test
    fun `cancellation propagates and is not counted as a failed attempt`() = runTest {
        val uploader =
            object : MediaUploader {
                var calls = 0
                override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
                    calls++
                    throw CancellationException("scope torn down")
                }
            }
        var rethrown = false
        try {
            RouteUploadRunner(uploader, delayFn = {}).upload(PATH, track)
        } catch (cancellation: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must propagate", rethrown)
        // No retry loop swallowed it into a second attempt.
        assertEquals(1, uploader.calls)
    }

    @Test
    fun `point conversions are a lossless round trip`() {
        val recorded = RecordedPoint(57.1, 12.2, 42L)
        assertEquals(recorded, recorded.toRoutePoint().toRecordedPoint())
        val route = RoutePoint(57.1, 12.2, 42L)
        assertEquals(route, route.toRecordedPoint().toRoutePoint())
    }
}

/**
 * Fake [MediaUploader] that records the last upload and can fail the first
 * [failFirst] attempts (to exercise the retry loop).
 */
private class RecordingUploader(
    private val failFirst: Int = 0,
    private val error: Exception = IllegalStateException("upload failed"),
) : MediaUploader {
    var attempts = 0
    var lastPath: String? = null
    var lastBytes: ByteArray? = null
    var lastContentType: String? = null

    override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
        attempts++
        if (attempts <= failFirst) throw error
        lastPath = path
        lastBytes = bytes
        lastContentType = contentType
        return path
    }
}
