package com.kungsbackacarcommunity.app.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The notification "Stop sharing" teardown, expressed as a plain suspending
 * function with no Android or Firebase dependency so it is unit-testable.
 *
 * ## Why this is not just `scope.launch { repo.stopSession() }; stopSelf()`
 * [LocationSharingService.stopSelf] does not destroy the service inline — the
 * platform posts `onDestroy()` back to the main thread — but it lands within a
 * few milliseconds, long before a Firebase callable completes. `onDestroy()`
 * cancels the service scope, so a `stopSession()` launched on that scope is
 * cancelled before it is even dispatched: measured at 199 cancellations out of
 * 200 launches. The user's "Stop sharing" tap would then stop the local service
 * while leaving the session ACTIVE server-side until its 1/2/4-hour expiry —
 * i.e. still broadcasting the last published position to viewers.
 *
 * The stop therefore runs on a process-lifetime scope that `onDestroy()` cannot
 * cancel, and [run] defers the caller's [finish] (`stopSelf`) until the callable
 * settles, so the service — and with it the process's service-priority — stays
 * alive for the round trip rather than racing it.
 *
 * [timeoutMillis] bounds that wait: on a dead network the callable must not keep
 * a stopped service resident indefinitely. Expiry is the server-side backstop.
 */
object LiveSharingStop {
    /** How long to wait for live.stopSession before giving up and stopping. */
    const val STOP_SESSION_TIMEOUT_MS = 10_000L

    /**
     * Runs [stopSession] best-effort, then always invokes [finish].
     *
     * [finish] runs exactly once, on every path: success, failure, timeout, and
     * cancellation of the caller. Leaving it unrun would strand the service.
     */
    suspend fun run(
        stopSession: suspend () -> Unit,
        finish: () -> Unit,
        timeoutMillis: Long = STOP_SESSION_TIMEOUT_MS,
    ) {
        try {
            withTimeoutOrNull(timeoutMillis) {
                try {
                    stopSession()
                } catch (c: CancellationException) {
                    // Cooperative cancellation (incl. the timeout) — never swallow.
                    throw c
                } catch (_: Exception) {
                    // The session expires on its own regardless, and publishing
                    // has already stopped locally. Details may reference the
                    // payload — never logged.
                }
            }
        } finally {
            finish()
        }
    }
}
