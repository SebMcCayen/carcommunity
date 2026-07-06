package com.kungsbackacarcommunity.app.live

import kotlinx.coroutines.flow.Flow

/** One GPS sample to publish via live.updatePosition. */
data class LiveCoordinate(
    val latitude: Double,
    val longitude: Double,
    val recordedAtIso: String,
    val accuracyMeters: Double? = null,
    val headingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
)

/**
 * Live-location session operations (Phase 12 slice 5). Firebase-free interface
 * so the coordinator and screen logic are JVM-unit-testable with fakes.
 *
 * Session state and markers live in Realtime Database under liveLocation/; all
 * writes flow through the member-gated callables (functions/src/live/session.ts).
 *
 * This interface observes the caller's OWN session only. Viewing OTHER members'
 * markers is deliberately the Map slice's concern: the target RTDB rules grant
 * a per-uid marker read (`liveLocation/{uid}/latest`) but no collection scan, so
 * the marker feed is built alongside the map, not here.
 */
interface LiveLocationRepository {
    /** live.startSession — (re)starts the caller's session with a duration. */
    suspend fun startSession(duration: LiveSessionDuration)

    /** live.updatePosition — publishes one sample (requires an active session). */
    suspend fun updatePosition(coordinate: LiveCoordinate)

    /** live.stopSession — stops sharing and removes the marker immediately. */
    suspend fun stopSession()

    /** live.hideMeNow — privacy stop; always available, even while suspended. */
    suspend fun hideMeNow()

    /** Live view of the caller's own session node; emits null when none. */
    fun observeOwnSession(uid: String): Flow<LiveSessionInfo?>
}
