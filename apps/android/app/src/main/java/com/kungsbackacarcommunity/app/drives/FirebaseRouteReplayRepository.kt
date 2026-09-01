package com.kungsbackacarcommunity.app.drives

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [RouteReplayRepository] that fetches a saved drive's route via the deployed
 * `drives-routeUrl` callable (europe-west1, App Check) instead of reading Cloud
 * Storage directly.
 *
 * The callable — given only `{ rideId }` — derives the owner from auth and
 * re-checks subscription-tier visibility SERVER-side, then returns a SHORT-LIVED
 * (≈5 min) V4 signed download URL `{ url, expiresAtMillis }`. This repository
 * then HTTP-GETs the bytes from that URL (no OkHttp in the app — a plain
 * [HttpURLConnection] on an IO dispatcher, capped at [MAX_ROUTE_BYTES]) and
 * decodes them with [RouteCodec]. Migrating the fetch here is what lets a later
 * PR lock down the direct-read `storage.rules` grant; that lockdown is NOT part
 * of this change.
 *
 * Guarded construction ([createIfAvailable] returns null with no Firebase), so
 * the config-less / CI build carries no reader and the detail screen degrades to
 * a placeholder.
 *
 * ## Every failure collapses to [RouteReplayState.Unavailable]
 * A callable error (`not-found` = missing/not-owned, `permission-denied` =
 * tier-hidden, `failed-precondition` = no file / signing unavailable), a network
 * fault, an HTTP non-200, an oversize download, or a decode failure ALL surface
 * as [RouteReplayState.Unavailable] — the reader never crashes to the UI. The
 * `uid`/`rideId` cache key and the public [RouteReplayRepository] contract are
 * unchanged, so the drive-detail screen and its tests are unaffected.
 *
 * ## Caching
 * A successful decode is cached IN MEMORY, keyed by uid+rideId, so re-opening a
 * drive during the session redraws instantly without a second callable +
 * download. Memory-only by design (no disk cache); failures are NOT cached, so a
 * transient error — or an expired signed URL — retries on the next open.
 */
class FirebaseRouteReplayRepository private constructor(
    private val fetchSignedUrl: suspend (rideId: String) -> String,
    private val downloadBytes: suspend (url: String) -> ByteArray,
) : RouteReplayRepository {

    private val cache = ConcurrentHashMap<String, List<RoutePoint>>()

    /**
     * Safe to call from the Main dispatcher (DrivesScreen invokes this from a
     * `LaunchedEffect`): the blocking network read and the CPU-bound
     * [RouteCodec.decode] run on [Dispatchers.IO], never on the caller's thread.
     */
    override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState {
        val key = "$uid/$rideId"
        cache[key]?.let { return RouteReplayState.Ready(it) }

        // One pipeline, one failure state: fetch the signed URL, download the
        // bytes, decode. Any throw (callable error, network, HTTP non-200,
        // oversize) OR a null decode collapses to Unavailable. The whole pipeline
        // runs on IO so the blocking HttpURLConnection read and the gzip/varint
        // decode never touch Main (NetworkOnMainThreadException / jank).
        // runCatchingCancellable (NOT plain runCatching) so a cancelled
        // LaunchedEffect / navigation propagates its CancellationException instead
        // of being swallowed into a spurious Unavailable.
        val points =
            withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val url = fetchSignedUrl(rideId)
                    val bytes = downloadBytes(url)
                    RouteCodec.decode(bytes)
                }.getOrNull()
            } ?: return RouteReplayState.Unavailable

        cache[key] = points
        return RouteReplayState.Ready(points)
    }

    companion object {
        private const val REGION = "europe-west1"

        /** The deployed callable (functions/src/drives/routeUrl.ts). */
        private const val ROUTE_URL_CALLABLE = "drives-routeUrl"
        private const val RIDE_ID = "rideId"
        private const val URL_KEY = "url"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_CHUNK_BYTES = 8 * 1024

        /**
         * Hard cap on the downloaded route file. Well above a real route (20 000
         * points ≈ 140 KB raw, less gzipped) yet bounded, so a corrupt/oversized
         * object fails the download instead of allocating unbounded memory. Kept
         * identical to the pre-migration `getBytes` cap.
         */
        internal const val MAX_ROUTE_BYTES: Long = 16L * 1024 * 1024

        fun createIfAvailable(context: Context): RouteReplayRepository? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            val functions = FirebaseFunctions.getInstance(REGION)
            return FirebaseRouteReplayRepository(
                fetchSignedUrl = { rideId -> callRouteUrl(functions, rideId) },
                downloadBytes = { url -> httpDownload(url) },
            )
        }

        /**
         * Test seam: inject a fake signed-URL provider and downloader so the
         * fetch→download→decode pipeline is exercised on the JVM without Firebase
         * or a live network. Package-internal — used only by the unit test.
         */
        internal fun createForTest(
            fetchSignedUrl: suspend (rideId: String) -> String,
            downloadBytes: suspend (url: String) -> ByteArray,
        ): FirebaseRouteReplayRepository =
            FirebaseRouteReplayRepository(fetchSignedUrl, downloadBytes)

        /**
         * Calls `drives-routeUrl` with `{ rideId }` and returns the signed `url`.
         * Mirrors the callable-invocation convention of the other Firebase repos
         * (FirebaseFunctions region instance + `getHttpsCallable`): a failed task
         * — any `FirebaseFunctionsException` code, App Check, or transport — throws
         * and is caught by [loadRoute] as Unavailable. A success with no usable url
         * is treated as a failure too (never returns a blank URL to download).
         */
        private suspend fun callRouteUrl(functions: FirebaseFunctions, rideId: String): String =
            suspendCancellableCoroutine { continuation ->
                functions
                    .getHttpsCallable(ROUTE_URL_CALLABLE)
                    .call(mapOf(RIDE_ID to rideId))
                    .addOnCompleteListener { task ->
                        if (!continuation.isActive) return@addOnCompleteListener
                        if (task.isSuccessful) {
                            val data = task.result?.data as? Map<*, *>
                            val url = data?.get(URL_KEY) as? String
                            if (url.isNullOrEmpty()) {
                                continuation.resumeWith(
                                    Result.failure(IOException("routeUrl returned no url")),
                                )
                            } else {
                                continuation.resume(url)
                            }
                        } else {
                            continuation.resumeWith(
                                Result.failure(
                                    task.exception ?: IOException("routeUrl call failed"),
                                ),
                            )
                        }
                    }
            }

        /**
         * Default downloader: a plain [HttpURLConnection] GET on the IO dispatcher
         * (no OkHttp in the app), bounded by [MAX_ROUTE_BYTES]. Throws on a non-200
         * response, an oversize body, or any transport fault; [loadRoute] maps the
         * throw to Unavailable. Package-internal so the unit test can drive the
         * real HttpURLConnection path against a loopback server.
         */
        internal suspend fun httpDownload(url: String): ByteArray =
            withContext(Dispatchers.IO) {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                try {
                    connection.connect()
                    val status = connection.responseCode
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw IOException("route download HTTP $status")
                    }
                    // Fail fast on an advertised over-cap length so a hostile
                    // server can't make us stream 16 MiB before aborting. The
                    // bounded reader below is still the backstop for chunked /
                    // unknown-length (-1) responses that lie or omit the header.
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > MAX_ROUTE_BYTES) {
                        throw IOException("route Content-Length $declaredLength exceeds $MAX_ROUTE_BYTES")
                    }
                    connection.inputStream.use { input -> readBounded(input) }
                } finally {
                    connection.disconnect()
                }
            }

        /**
         * Reads the stream into a bounded buffer, aborting the moment the total
         * would exceed [MAX_ROUTE_BYTES] so a hostile/corrupt body can never
         * allocate unbounded memory.
         */
        private fun readBounded(input: java.io.InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(DOWNLOAD_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read == -1) break
                total += read
                if (total > MAX_ROUTE_BYTES) {
                    throw IOException("route exceeds $MAX_ROUTE_BYTES bytes")
                }
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }
}
