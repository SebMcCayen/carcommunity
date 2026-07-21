package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.media.MediaUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * [RecordedPoint] (the recorder's fix) and [RoutePoint] (the codec's decoded
 * fix) are the SAME triple — latitude, longitude, timestampMs — living in two
 * layers so neither the recorder nor the reader depends on the other. These
 * conversions are the single, obvious bridge between them: the write side
 * encodes recorded fixes via [toRoutePoint]. The read side derives top speed
 * straight off the decoded [RoutePoint]s (DriveSummary has a [RoutePoint]
 * overload, so it needs no conversion); [toRecordedPoint] remains the inverse
 * bridge, pinned by the round-trip test.
 */
fun RecordedPoint.toRoutePoint(): RoutePoint = RoutePoint(latitude, longitude, timestampMs)

fun RoutePoint.toRecordedPoint(): RecordedPoint = RecordedPoint(latitude, longitude, timestampMs)

/** Outcome of a route-file upload attempt sequence. Pure, so it is assertable. */
sealed interface RouteUploadOutcome {
    /** The route file was written to Cloud Storage. */
    data object Uploaded : RouteUploadOutcome

    /**
     * Nothing to upload: a summary-only save (no route points) has no `route.bin`
     * to write, and the reader already renders such a drive as "route empty".
     */
    data object Skipped : RouteUploadOutcome

    /**
     * Every attempt failed. The drive doc still exists; the reader degrades to
     * "route unavailable", so this is a tolerated end state rather than a crash —
     * but it is surfaced (not swallowed) so a caller could log/report it.
     *
     * @property attempts how many uploads were tried before giving up.
     * @property cause the last failure, for diagnostics.
     */
    data class Failed(val attempts: Int, val cause: Throwable?) : RouteUploadOutcome
}

/**
 * Encodes a recorded drive's route and uploads it to the canonical
 * `route.bin` Cloud Storage path with bounded retries. Pure Kotlin (the actual
 * `putBytes` lives behind [MediaUploader]) so the whole retry state machine is
 * JVM-unit-testable with a fake uploader and no real delays.
 *
 * ## Why this is a background, best-effort step
 * `drives-save` creates the drive doc and returns the route path but does NOT
 * write the file — the client does. That makes the upload a SECOND step after a
 * save the user already sees as complete, so it must never block or fail the
 * save UX: [DriveRecordingCoordinator] launches [upload] on a process-scoped
 * scope after moving to `Saved`. If every attempt still fails, the drive simply
 * has no route file and the reader shows "route unavailable" ([RouteCodec]
 * decodes a missing file to that state) — acceptable, and recoverable because an
 * idempotent re-save re-runs this upload.
 *
 * ## gzip
 * The payload is always gzipped ([RouteCodec.encode] with `gzip = true`): the
 * reader auto-detects the gzip magic and inflates, `storage.rules` permits
 * `application/gzip` for `route.bin`, and a real route (hundreds–thousands of
 * fixes) compresses well. The ~18-byte gzip header is negligible for the tiny
 * routes where it doesn't help.
 */
class RouteUploadRunner(
    private val uploader: MediaUploader,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffMillis: (attempt: Int) -> Long = ::defaultBackoffMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        // A programming constant, not user input: 0/negative would make the retry
        // loop run zero times and return Failed(attempts = <= 0) without ever
        // calling the uploader — a silent misconfiguration. Fail loud at
        // construction instead. (Production uses DEFAULT_MAX_ATTEMPTS.)
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    }

    /**
     * Encodes [points] and uploads the gzipped `route.bin` to [routePath],
     * retrying transient failures up to [maxAttempts] with backoff. Returns
     * [RouteUploadOutcome.Skipped] with no points (nothing to write),
     * [RouteUploadOutcome.Uploaded] on success, or [RouteUploadOutcome.Failed]
     * once the attempts are exhausted (or if encoding fails, `attempts = 0`).
     * Cooperative cancellation is preserved (a [CancellationException] is
     * rethrown, never counted as a failed attempt).
     */
    suspend fun upload(routePath: String, points: List<RecordedPoint>): RouteUploadOutcome {
        if (points.isEmpty()) return RouteUploadOutcome.Skipped
        // Encode the SAME fixes the backend priced its stats from, so replay and
        // top-speed match the stored summary. Encoding is in-memory gzip and has
        // no throw path for valid points, but this runs fire-and-forget on a
        // background scope, so any unexpected failure is turned into a Failed
        // outcome (attempts = 0, nothing was uploaded) rather than escaping the
        // launch as an uncaught exception.
        val bytes =
            try {
                RouteCodec.encode(points.map { it.toRoutePoint() }, gzip = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return RouteUploadOutcome.Failed(attempts = 0, cause = error)
            }

        var lastError: Throwable? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            try {
                uploader.upload(routePath, bytes, CONTENT_TYPE_GZIP)
                return RouteUploadOutcome.Uploaded
            } catch (cancellation: CancellationException) {
                // Navigation away / scope teardown: not a failed upload attempt.
                throw cancellation
            } catch (error: Exception) {
                lastError = error
                attempt++
                if (attempt < maxAttempts) delayFn(backoffMillis(attempt - 1))
            }
        }
        return RouteUploadOutcome.Failed(attempts = maxAttempts, cause = lastError)
    }

    companion object {
        /**
         * Content type for the gzipped route file. Must be one of the types
         * `storage.rules` permits for `rideRoutes/{uid}/{rideId}/route.bin`
         * (`application/octet-stream|gzip|x-gzip`); `RouteCodec` gzips, so gzip.
         */
        const val CONTENT_TYPE_GZIP = "application/gzip"

        /** Total upload attempts before giving up (1 initial + 2 retries). */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** Backoff before retry N (0-based). Bounded, coarse — this is background. */
        private val BACKOFF_MILLIS = longArrayOf(1_000L, 4_000L)

        fun defaultBackoffMillis(attempt: Int): Long =
            BACKOFF_MILLIS.getOrElse(attempt) { BACKOFF_MILLIS.last() }
    }
}
