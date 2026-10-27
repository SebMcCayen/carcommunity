package com.kungsbackacarcommunity.app.drives

/** Failures are safe, stable categories; never expose a signed URL or raw backend error. */
enum class RouteReplayFailure(val canRetry: Boolean) {
    /** May also be an upload still in flight immediately after saving. */
    MISSING(true),
    SIGN_IN_REQUIRED(false),
    ACCESS_DENIED(false),
    NOT_FOUND(false),
    SERVICE_UNAVAILABLE(true),
    DOWNLOAD_FAILED(true),
    CORRUPT(false),
    UNAVAILABLE(true),
}

sealed interface RouteReplayState {
    data object Loading : RouteReplayState

    data class Unavailable(val reason: RouteReplayFailure = RouteReplayFailure.UNAVAILABLE) : RouteReplayState

    /** Only full recorded fixes carry timestamps and may supply replay-derived statistics. */
    data class Ready(val points: List<RoutePoint>) : RouteReplayState
}

/**
 * Loads full CCRB route bytes through the owner/tier-authorized `drives-routeUrl`
 * callable. Each load must recheck server authority, including repeat opens.
 * Missing legacy GPS is never reconstructed from the simplified thumbnail.
 */
interface RouteReplayRepository {
    /** Cancellation propagates; other faults return a categorized [RouteReplayState.Unavailable]. */
    suspend fun loadRoute(uid: String, rideId: String): RouteReplayState
}

/** Firebase's Android-dependent exception is translated at the callable boundary. */
internal class RouteReplayCallableException(
    val code: String?,
    val publicMessage: String?,
) : Exception()

/**
 * Current deployed routeUrl has no structured details for preconditions. Match
 * only its exact public missing-file message; unknown preconditions stay retryable.
 */
internal fun routeReplayCallableFailure(code: String?, publicMessage: String?): RouteReplayFailure =
    when (code) {
        "UNAUTHENTICATED" -> RouteReplayFailure.SIGN_IN_REQUIRED
        "PERMISSION_DENIED" -> RouteReplayFailure.ACCESS_DENIED
        "NOT_FOUND" -> RouteReplayFailure.NOT_FOUND
        "FAILED_PRECONDITION" ->
            if (publicMessage == "This drive has no stored route to replay.") RouteReplayFailure.MISSING
            else RouteReplayFailure.SERVICE_UNAVAILABLE
        "UNAVAILABLE", "DEADLINE_EXCEEDED", "RESOURCE_EXHAUSTED", "INTERNAL" ->
            RouteReplayFailure.SERVICE_UNAVAILABLE
        else -> RouteReplayFailure.UNAVAILABLE
    }
