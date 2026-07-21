package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a live list of nearby STANDALONE live sharers to draw on the map,
 * refreshed by polling `live.listNearby` around the current map centre.
 *
 * The discovery half of the "nearby/public" live-session feature: a solo sharer
 * whose uid is on no convoy/group roster is found here instead. [nearbySharers]
 * holds only the discovery SEEDS (uid + last-known position); the map layer
 * subscribes each uid's existing per-uid RTDB `observeLatest` for the live
 * stream, so this controller never holds the high-frequency position feed — it
 * only answers "who is around, and where were they".
 *
 * Deliberately UI-framework-light (a StateFlow + one suspend entry point) and
 * built on the injected [LiveLocationRepository], so it is JVM-unit-testable
 * with a fake repository. Mirrors IncidentReportController's refresh semantics:
 * a failed fetch leaves the previous list intact rather than clearing the map.
 */
class NearbyLiveController(
    private val repository: LiveLocationRepository,
) {
    private val nearbyFlow = MutableStateFlow<List<NearbyLiveSession>>(emptyList())

    /** Active nearby sharers near the last refreshed centre, for the map layer. */
    val nearbySharers: StateFlow<List<NearbyLiveSession>> = nearbyFlow.asStateFlow()

    /**
     * Refreshes [nearbySharers] around [center]. A fetch failure leaves the
     * previous list intact (the map keeps the last-known sharers) rather than
     * clearing it; cancellation propagates so structured concurrency is honoured.
     */
    suspend fun refresh(center: LatLng, radiusMeters: Double = DEFAULT_NEARBY_RADIUS_METERS) {
        val fetched =
            try {
                repository.listNearby(center, radiusMeters)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return
            }
        nearbyFlow.value = fetched
    }

    /** Drops all nearby sharers (e.g. when the layer is turned off). */
    fun clear() {
        nearbyFlow.value = emptyList()
    }
}
