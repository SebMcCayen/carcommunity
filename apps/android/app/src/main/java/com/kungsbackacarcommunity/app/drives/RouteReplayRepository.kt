package com.kungsbackacarcommunity.app.drives

/**
 * The outcome of loading a saved drive's driven route for History replay.
 *
 * [Ready] carries the decoded points (possibly EMPTY — a summary-only drive that
 * was saved without route points); [Unavailable] is the single clean failure
 * state for everything that can go wrong reading `route.bin` — the file does not
 * exist, the storage read is denied (route files are member-gated), a network
 * error, or a corrupt/truncated payload. The reader NEVER surfaces a crash to
 * the UI; every fault collapses to [Unavailable].
 *
 * [Loading] is a UI-only starting state and is never returned by the repository.
 */
sealed interface RouteReplayState {
    data object Loading : RouteReplayState

    data object Unavailable : RouteReplayState

    data class Ready(val points: List<RoutePoint>) : RouteReplayState
}

/**
 * Reads a saved drive's route file (`rideRoutes/{uid}/{rideId}/route.bin`) from
 * member-gated Cloud Storage and decodes it via [RouteCodec]. Firebase-free
 * interface so the drive-detail screen and its tests do not depend on Storage.
 */
interface RouteReplayRepository {
    /**
     * Loads and decodes the route for [rideId] owned by [uid]. Returns
     * [RouteReplayState.Ready] on success (points may be empty) or
     * [RouteReplayState.Unavailable] for any missing-file / permission / network
     * / decode failure. Implementations should cache a successful result in
     * memory so re-opening the same drive does not refetch.
     */
    suspend fun loadRoute(uid: String, rideId: String): RouteReplayState
}
