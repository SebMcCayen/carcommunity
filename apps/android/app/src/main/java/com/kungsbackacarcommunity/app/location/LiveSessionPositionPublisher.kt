package com.kungsbackacarcommunity.app.location

import com.kungsbackacarcommunity.app.live.LiveCoordinate
import com.kungsbackacarcommunity.app.live.LiveLocation
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One serial upload worker per observed session. All entry points and [scope]
 * must use the same dispatcher (the service's main dispatcher in production).
 * Only the newest accepted fix is retained, in memory, for bounded retries.
 * Cancelling a run prevents further dispatch; an already-sent callable cannot
 * be recalled, so the backend's session/privacy fences remain authoritative.
 */
internal class LiveSessionPositionPublisher(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val publish: suspend (LiveCoordinate) -> Unit,
) {
    private class Run(val id: String) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
        var latest: LiveCoordinate? = null
        var enabled = false
        var expiresAtMillis: Long? = null
        var discoveryReady = false
        var uploadConfirmed = false
        var attempts = 0
        var lastSubmitted: LiveCoordinate? = null
        var lastSubmittedAt: Long? = null
    }

    private var run: Run? = null
    val sessionId: String? get() = run?.takeIf { it.enabled }?.id

    /** Returns true only when a different active session starts a fresh run. */
    fun observeSession(session: LiveSessionInfo?): Boolean {
        if (!LiveLocation.isSharing(session, nowMillis())) {
            // A transient absent observation pauses uploads without granting the
            // SAME session another startup budget when its snapshot returns.
            run?.let {
                it.enabled = false
                it.job?.cancel()
                it.job = null
                it.latest = null
            }
            return false
        }
        val id = session!!.sessionId
        val replaced = run?.id != id
        if (replaced) {
            stop()
            run = Run(id)
        }
        val current = run!!
        current.enabled = true
        current.expiresAtMillis = session.expiresAtMillis
        // This owner-only bookkeeping is written AFTER discovery succeeds.
        // HTTP success alone is insufficient: the discovery fence can still be
        // awaiting activation when the first position reaches the backend.
        current.discoveryReady = session.discoveryRefreshedAtMillis != null
        if (current.job?.isActive != true) current.job = scope.launch { upload(current) }
        current.wake.trySend(Unit)
        return replaced
    }

    /** The caller has already applied the raw provider's quality gate. */
    fun accept(coordinate: LiveCoordinate) {
        val current = run?.takeIf { it.enabled } ?: return
        if (!isFresh(coordinate, nowMillis())) return
        current.latest = coordinate
        current.wake.trySend(Unit)
    }

    fun stop() {
        run?.job?.cancel()
        run?.wake?.close()
        run = null
    }

    private suspend fun upload(current: Run) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val coordinate = current.latest
            val now = nowMillis()
            if (run !== current || !current.enabled || current.expiresAtMillis?.let { now >= it } == true) return
            if (coordinate == null || !isFresh(coordinate, now)) {
                current.wake.receive()
                continue
            }
            val previous = current.lastSubmitted
            val ordinary = BackgroundLocation.shouldPublish(
                current.lastSubmittedAt, previous?.latitude, previous?.longitude,
                coordinate.latitude, coordinate.longitude, now,
            )
            val startupAt = if (
                !(current.discoveryReady && current.uploadConfirmed) &&
                current.attempts in 1 until MAX_STARTUP_ATTEMPTS
            ) {
                current.lastSubmittedAt!! + STARTUP_RETRY_DELAYS_MS[current.attempts - 1]
            } else null
            if (ordinary || (startupAt != null && now >= startupAt)) {
                current.lastSubmitted = coordinate
                current.lastSubmittedAt = now
                current.attempts++
                try {
                    publish(coordinate)
                    current.uploadConfirmed = true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // No payload/error logging. Retry is bounded independently
                    // of provider cadence, and never resets on HTTP success.
                }
                continue
            }
            val heartbeatAt = current.lastSubmittedAt!! + BackgroundLocation.STATIONARY_HEARTBEAT_MS
            val nextAt = startupAt?.let { minOf(it, heartbeatAt) } ?: heartbeatAt
            withTimeoutOrNull((nextAt - now).coerceAtLeast(1L)) { current.wake.receive() }
        }
    }

    companion object {
        // At most four EXTRA writes per observed session. Exhaustion falls back
        // to the normal movement/heartbeat policy even if discovery never lands.
        internal const val MAX_STARTUP_ATTEMPTS = 5
        private val STARTUP_RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000)
        private const val MAX_FIX_AGE_MS = 60_000L
        private const val MAX_FUTURE_SKEW_MS = 30_000L

        private fun isFresh(coordinate: LiveCoordinate, nowMillis: Long): Boolean {
            val recorded = try {
                Instant.parse(coordinate.recordedAtIso).toEpochMilli()
            } catch (_: Exception) {
                return false
            }
            return isFreshFixTime(recorded, nowMillis)
        }

        internal fun isFreshFixTime(recordedAtMillis: Long, nowMillis: Long): Boolean =
            nowMillis - recordedAtMillis in -MAX_FUTURE_SKEW_MS..MAX_FIX_AGE_MS
    }
}
