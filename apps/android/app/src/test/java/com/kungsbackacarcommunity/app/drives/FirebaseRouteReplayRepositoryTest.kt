package com.kungsbackacarcommunity.app.drives

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
 * Successful loads decode full fixes; categorized failures remain distinguishable.
 * Every open rechecks authority and cancellation always propagates.
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
                authenticatedUid = { "uid-1" },
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = { encoded },
            )

        val state = repo.loadRoute("uid-1", "ride-1")

        assertTrue("expected Ready but was $state", state is RouteReplayState.Ready)
        assertEquals(expected, (state as RouteReplayState.Ready).points)
    }

    @Test
    fun `callable errors retain missing auth access and service distinctions`() = runTest {
        val cases = listOf(
            Triple("NOT_FOUND", "Saved drive not found.", RouteReplayFailure.NOT_FOUND),
            Triple("UNAUTHENTICATED", "Sign in to continue.", RouteReplayFailure.SIGN_IN_REQUIRED),
            Triple("PERMISSION_DENIED", "Account access is restricted.", RouteReplayFailure.ACCESS_DENIED),
            Triple("PERMISSION_DENIED", "This drive is outside your plan’s history window.", RouteReplayFailure.ACCESS_DENIED),
            Triple("FAILED_PRECONDITION", "This drive has no stored route to replay.", RouteReplayFailure.MISSING),
            Triple("FAILED_PRECONDITION", "Route replay is temporarily unavailable.", RouteReplayFailure.SERVICE_UNAVAILABLE),
            Triple("FAILED_PRECONDITION", "future precondition", RouteReplayFailure.SERVICE_UNAVAILABLE),
            Triple("UNAVAILABLE", "unavailable", RouteReplayFailure.SERVICE_UNAVAILABLE),
        )
        for ((code, message, expected) in cases) {
            val repo = FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { "uid-1" },
                fetchSignedUrl = { throw RouteReplayCallableException(code, message) },
                downloadBytes = { throw AssertionError("Denied calls must never download") },
            )
            assertEquals(RouteReplayState.Unavailable(expected), repo.loadRoute("uid-1", "ride-1"))
        }
    }

    @Test
    fun `callable network error is a retryable service error`() = runTest {
        val repo = FirebaseRouteReplayRepository.createForTest(
            authenticatedUid = { "uid-1" },
            fetchSignedUrl = { throw IOException("offline") },
            downloadBytes = { throw AssertionError("must not download") },
        )
        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.SERVICE_UNAVAILABLE), repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `download failure is distinct from a callable service failure`() = runTest {
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { "uid-1" },
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = { throw IOException("connection reset") },
            )

        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.DOWNLOAD_FAILED), repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `cancellation propagates and is not collapsed to Unavailable`() = runTest {
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { "uid-1" },
                fetchSignedUrl = { throw CancellationException("cancelled") },
                downloadBytes = { throw AssertionError("downloader must not run") },
            )

        val thrown =
            try {
                repo.loadRoute("uid-1", "ride-1")
                null
            } catch (c: CancellationException) {
                c
            }
        assertTrue("expected CancellationException to propagate but got $thrown", thrown != null)
    }

    @Test
    fun `decode returning null collapses to Unavailable`() = runTest {
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { "uid-1" },
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = { byteArrayOf(0x00, 0x01, 0x02, 0x03) }, // not a CCRB payload
            )

        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.CORRUPT), repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `second load for the same uid and rideId rechecks server authority`() = runTest {
        val encoded = RouteCodec.encode(samplePoints)
        val callableCalls = AtomicInteger(0)
        val downloads = AtomicInteger(0)
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { "uid-1" },
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
        assertEquals(2, callableCalls.get())
        assertEquals(2, downloads.get())
    }

    @Test
    fun `a failed load is not cached and retries`() = runTest {
        val encoded = RouteCodec.encode(samplePoints)
        val attempts = AtomicInteger(0)
        val repo =
            FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { "uid-1" },
                fetchSignedUrl = { "https://signed.example/route.bin" },
                downloadBytes = {
                    // First attempt fails, second succeeds — proves failures are
                    // not cached.
                    if (attempts.getAndIncrement() == 0) throw IOException("transient") else encoded
                },
            )

        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.DOWNLOAD_FAILED), repo.loadRoute("uid-1", "ride-1"))
        assertTrue(repo.loadRoute("uid-1", "ride-1") is RouteReplayState.Ready)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `a cached success cannot bypass a later tier or account denial`() = runTest {
        var calls = 0
        var downloads = 0
        val repo = FirebaseRouteReplayRepository.createForTest(
            authenticatedUid = { "uid-1" },
            fetchSignedUrl = {
                if (calls++ > 0) throw RouteReplayCallableException("PERMISSION_DENIED", "denied")
                "https://signed.example/route.bin"
            },
            downloadBytes = { downloads++; RouteCodec.encode(samplePoints, gzip = true) },
        )
        assertTrue(repo.loadRoute("uid-1", "ride-1") is RouteReplayState.Ready)
        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.ACCESS_DENIED), repo.loadRoute("uid-1", "ride-1"))
        assertEquals(1, downloads)
    }

    @Test
    fun `wrong owner and signed out loads do not call backend`() = runTest {
        for (currentUid in listOf(null, "uid-2")) {
            val repo = FirebaseRouteReplayRepository.createForTest(
                authenticatedUid = { currentUid },
                fetchSignedUrl = { throw AssertionError("must not call under different auth") },
                downloadBytes = { throw AssertionError("must not download") },
            )
            assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.SIGN_IN_REQUIRED), repo.loadRoute("uid-1", "ride-1"))
        }
    }

    @Test
    fun `account switch during URL fetch prevents download`() = runTest {
        var uid = "uid-1"
        val repo = FirebaseRouteReplayRepository.createForTest(
            authenticatedUid = { uid },
            fetchSignedUrl = { uid = "uid-2"; "https://signed.example/route.bin" },
            downloadBytes = { throw AssertionError("must not download after switch") },
        )
        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.SIGN_IN_REQUIRED), repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `account switch during download drops the private points`() = runTest {
        var uid = "uid-1"
        val repo = FirebaseRouteReplayRepository.createForTest(
            authenticatedUid = { uid },
            fetchSignedUrl = { "https://signed.example/route.bin" },
            downloadBytes = { uid = "uid-2"; RouteCodec.encode(samplePoints) },
        )
        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.SIGN_IN_REQUIRED), repo.loadRoute("uid-1", "ride-1"))
    }

    @Test
    fun `retry requests a new signed URL after an expired download URL`() = runTest {
        var calls = 0
        val urls = mutableListOf<String>()
        val repo = FirebaseRouteReplayRepository.createForTest(
            authenticatedUid = { "uid-1" },
            fetchSignedUrl = { "https://signed.example/route.bin?attempt=${++calls}" },
            downloadBytes = { url ->
                urls += url
                if (urls.size == 1) throw IOException("HTTP 403")
                RouteCodec.encode(samplePoints, gzip = true)
            },
        )
        assertEquals(RouteReplayState.Unavailable(RouteReplayFailure.DOWNLOAD_FAILED), repo.loadRoute("uid-1", "ride-1"))
        assertTrue(repo.loadRoute("uid-1", "ride-1") is RouteReplayState.Ready)
        assertEquals(2, urls.distinct().size)
    }

    @Test
    fun `download cancellation propagates too`() = runTest {
        val repo = FirebaseRouteReplayRepository.createForTest(
            authenticatedUid = { "uid-1" },
            fetchSignedUrl = { "https://signed.example/route.bin" },
            downloadBytes = { throw CancellationException("left detail") },
        )
        try {
            repo.loadRoute("uid-1", "ride-1")
            throw AssertionError("cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected, never an error state.
        }
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
    fun `httpDownload aborts an oversize body with no declared length`() = runTest {
        // NO Content-Length header (unknown-length / read-until-close), so the
        // fast-fail header check is skipped and readBounded's mid-stream abort is
        // the path under test: stream just over the 16 MiB cap and it must abort
        // rather than buffer it all. (The declared-over-cap fast-fail is covered
        // by the separate test below.)
        val oversize = FirebaseRouteReplayRepository.MAX_ROUTE_BYTES + 4_096
        withRawServer({ out ->
            out.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray())
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
