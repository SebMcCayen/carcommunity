package com.kungsbackacarcommunity.app.drives

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the signed-URL migration of [FirebaseRouteReplayRepository].
 *
 * The pipeline (fetch signed URL → download bytes → [RouteCodec.decode]) is
 * exercised through the package-internal test seam ([FirebaseRouteReplayRepository.createForTest])
 * with a fake signed-URL provider and a fake downloader, so no Firebase or live
 * network is needed. Separately, the REAL [java.net.HttpURLConnection] download
 * path ([FirebaseRouteReplayRepository.httpDownload]) is driven against a raw
 * loopback [java.net.ServerSocket] responder (deliberately NOT
 * `com.sun.net.httpserver`, which is absent from the Android unit-test
 * classpath) to cover the success / non-200 / oversize behaviour that the fake
 * downloader cannot.
 *
 * The single observable contract is: success → [RouteReplayState.Ready];
 * EVERYTHING else → [RouteReplayState.Unavailable].
 */
class FirebaseRouteReplayRepositoryTest {

    private val samplePoints =
        listOf(
            RoutePoint(latitude = 57.4870, longitude = 12.0700, timestampMs = 1_000L),
            RoutePoint(latitude = 57.4880, longitude = 12.0720, timestampMs = 2_000L),
            RoutePoint(latitude = 57.4895, longitude = 12.0755, timestampMs = 3_500L),
        )

    // ---- pipeline via the injectable seams ---------------------------------

    @Test
    fun `callable success plus download success decodes to Ready`() = runTest {
        val encoded = RouteCodec.encode(samplePoints)
        val expected = RouteCodec.decode(encoded)!!
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = { encoded },
            )

        val state = repo.loadRoute("uid-1", "ride-1")

        assertTrue("expected Ready but was $state", state is RouteReplayState.Ready)
        assertEquals(expected, (state as RouteReplayState.Ready).points)
    }

    @Test
    fun `callable not-found collapses to Unavailable`() = runTest {
        assertUnavailableWhenCallableThrows(RuntimeException("not-found"))
    }

    @Test
    fun `callable permission-denied collapses to Unavailable`() = runTest {
        assertUnavailableWhenCallableThrows(RuntimeException("permission-denied"))
    }

    @Test
    fun `callable failed-precondition collapses to Unavailable`() = runTest {
        assertUnavailableWhenCallableThrows(RuntimeException("failed-precondition"))
    }

    @Test
    fun `callable network error collapses to Unavailable`() = runTest {
        assertUnavailableWhenCallableThrows(IOException("offline"))
    }

    private suspend fun assertUnavailableWhenCallableThrows(error: Throwable) {
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                fetchSignedUrl = { throw error },
                downloadBytes = { error("downloader must not run when the callable fails") },
            )

        assertEquals(RouteReplayState.Unavailable, repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `download failure collapses to Unavailable`() = runTest {
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = { throw IOException("connection reset") },
            )

        assertEquals(RouteReplayState.Unavailable, repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `decode returning null collapses to Unavailable`() = runTest {
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = { byteArrayOf(0x00, 0x01, 0x02, 0x03) }, // not a CCRB payload
            )

        assertEquals(RouteReplayState.Unavailable, repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `second load for the same uid and rideId hits the cache`() = runTest {
        val encoded = RouteCodec.encode(samplePoints)
        val callableCalls = AtomicInteger(0)
        val downloads = AtomicInteger(0)
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                fetchSignedUrl = {
                    callableCalls.incrementAndGet()
                    "https://signed.example/route.bin"
                },
                downloadBytes = {
                    downloads.incrementAndGet()
                    encoded
                },
            )

        val first = repo.loadRoute("uid-1", "ride-1")
        val second = repo.loadRoute("uid-1", "ride-1")

        assertTrue(first is RouteReplayState.Ready)
        assertTrue(second is RouteReplayState.Ready)
        assertEquals(1, callableCalls.get())
        assertEquals(1, downloads.get())
    }

    @Test
    fun `a failed load is not cached and retries`() = runTest {
        val encoded = RouteCodec.encode(samplePoints)
        val attempts = AtomicInteger(0)
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = {
                    // First attempt fails, second succeeds — proves failures are
                    // not cached.
                    if (attempts.getAndIncrement() == 0) throw IOException("transient") else encoded
                },
            )

        assertEquals(RouteReplayState.Unavailable, repo.loadRoute("uid-1", "ride-1"))
        assertTrue(repo.loadRoute("uid-1", "ride-1") is RouteReplayState.Ready)
        assertEquals(2, attempts.get())
    }

    // ---- real HttpURLConnection download path against a loopback server ----

    @Test
    fun `httpDownload returns the served bytes on 200`() = runTest {
        val payload = RouteCodec.encode(samplePoints)
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(payload)
            out.flush()
        }) { url ->
            val bytes = FirebaseRouteReplayRepository.httpDownload(url)
            assertTrue(payload.contentEquals(bytes))
        }
    }

    @Test
    fun `httpDownload throws on a non-200 response`() = runTest {
        withRawServer({ out ->
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }) { url ->
            assertThrowsIo { FirebaseRouteReplayRepository.httpDownload(url) }
        }
    }

    @Test
    fun `httpDownload aborts an oversize body`() = runTest {
        // Stream just over the 16 MiB cap; readBounded must abort rather than
        // buffer it all.
        val oversize = FirebaseRouteReplayRepository.MAX_ROUTE_BYTES + 4_096
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: $oversize\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = ByteArray(64 * 1024)
            var remaining = oversize
            try {
                while (remaining > 0) {
                    val n = minOf(remaining, chunk.size.toLong()).toInt()
                    out.write(chunk, 0, n)
                    remaining -= n
                }
                out.flush()
            } catch (_: IOException) {
                // The client aborts once it passes the cap; the broken pipe is
                // expected.
            }
        }) { url ->
            assertThrowsIo { FirebaseRouteReplayRepository.httpDownload(url) }
        }
    }

    @Test
    fun `httpDownload fails fast on an over-cap Content-Length without reading the body`() =
        runTest {
            // Advertise an over-cap length and send NO body. Fail-fast must throw
            // from the header alone; without it, readBounded would just hit EOF and
            // return an empty array (no throw) — so this distinguishes the two.
            val declared = FirebaseRouteReplayRepository.MAX_ROUTE_BYTES + 1
            withRawServer({ out ->
                out.write("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }) { url ->
                assertThrowsIo { FirebaseRouteReplayRepository.httpDownload(url) }
            }
        }

    // ---- helpers ------------------------------------------------------------

    private suspend fun assertThrowsIo(block: suspend () -> Unit) {
        val thrown =
            try {
                block()
                null
            } catch (e: Throwable) {
                e
            }
        assertTrue("expected an IOException but got $thrown", thrown is IOException)
    }

    /**
     * Minimal loopback HTTP/1.1 responder (no `com.sun.net.httpserver` — it is not
     * on the Android unit-test classpath). Accepts one connection, drains the
     * request headers, then hands the socket's [OutputStream] to [respond] to
     * write a raw response.
     */
    private inline fun withRawServer(
        crossinline respond: (OutputStream) -> Unit,
        block: (String) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker =
            Thread {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        // Consume the request line + headers up to the blank line.
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        respond(socket.getOutputStream())
                    }
                } catch (_: Exception) {
                    // Server socket closed / client hung up — nothing to do.
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            block("http://127.0.0.1:${server.localPort}/route.bin")
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
