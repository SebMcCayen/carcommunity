package com.kungsbackacarcommunity.app.drives

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Fetches owner-authorized, short-lived URLs from `drives-routeUrl` (europe-west1,
 * App Check), then downloads bounded CCRB bytes on IO. Never falls back to direct
 * Storage reads: missing files and signing errors must not bypass backend gates.
 *
 * Successful points are deliberately not cached across loads: an account restriction,
 * tier downgrade or deleted drive must be checked by the server on every reopen.
 * The UI holds points only for the lifetime of its keyed detail composition.
 */
class FirebaseRouteReplayRepository private constructor(
    private val fetchSignedUrl: suspend (rideId: String) -> String,
    private val downloadBytes: suspend (url: String) -> ByteArray,
    private val authenticatedUid: () -> String?,
) : RouteReplayRepository {
    override suspend fun loadRoute(uid: String, rideId: String): RouteReplayState =
        withContext(Dispatchers.IO) {
            if (uid.isBlank() || authenticatedUid() != uid) {
                return@withContext RouteReplayState.Unavailable(RouteReplayFailure.SIGN_IN_REQUIRED)
            }
            try {
                val url = fetchSignedUrl(rideId)
                if (authenticatedUid() != uid) {
                    return@withContext RouteReplayState.Unavailable(RouteReplayFailure.SIGN_IN_REQUIRED)
                }
                val bytes = try {
                    downloadBytes(url)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@withContext RouteReplayState.Unavailable(RouteReplayFailure.DOWNLOAD_FAILED)
                }
                // Auth can change while a signed-URL download is in flight.
                if (authenticatedUid() != uid) {
                    return@withContext RouteReplayState.Unavailable(RouteReplayFailure.SIGN_IN_REQUIRED)
                }
                val points = RouteCodec.decode(bytes)
                    ?: return@withContext RouteReplayState.Unavailable(RouteReplayFailure.CORRUPT)
                RouteReplayState.Ready(points)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val reason = when (failure) {
                    is RouteReplayCallableException -> routeReplayCallableFailure(failure.code, failure.publicMessage)
                    is IOException -> RouteReplayFailure.SERVICE_UNAVAILABLE
                    else -> RouteReplayFailure.UNAVAILABLE
                }
                RouteReplayState.Unavailable(reason)
            }
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
                authenticatedUid = { FirebaseAuth.getInstance().currentUser?.uid },
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
            authenticatedUid: () -> String?,
        ): FirebaseRouteReplayRepository =
            FirebaseRouteReplayRepository(fetchSignedUrl, downloadBytes, authenticatedUid)

        /**
         * Calls `drives-routeUrl` with `{ rideId }` and returns the signed `url`.
         * Mirrors the callable-invocation convention of the other Firebase repos
         * (FirebaseFunctions region instance + `getHttpsCallable`): a failed task
         * — any `FirebaseFunctionsException` code, App Check, or transport — throws
         * and is categorized by [loadRoute]. A success with no usable url
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
                            if (url.isNullOrBlank()) {
                                continuation.resumeWith(
                                    Result.failure(IOException("routeUrl returned no url")),
                                )
                            } else {
                                continuation.resume(url)
                            }
                        } else {
                            val error = task.exception as? FirebaseFunctionsException
                            continuation.resumeWith(
                                Result.failure(
                                    if (error != null) RouteReplayCallableException(error.code.name, error.message)
                                    else IOException("routeUrl call failed"),
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
