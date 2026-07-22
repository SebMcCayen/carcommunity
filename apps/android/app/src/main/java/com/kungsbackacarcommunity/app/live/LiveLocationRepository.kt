package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.flow.Flow

/**
 * Default nearby-discovery radius (metres) — the same 15 km default the backend
 * clamps to (functions/src/incidents/incidents-core.ts DEFAULT_RADIUS_METERS,
 * shared by live.listNearby). Covers a comfortable map viewport without asking
 * for the whole country.
 */
const val DEFAULT_NEARBY_RADIUS_METERS: Double = 15_000.0

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
 * The sharer's main car, denormalized onto the live marker so a viewer sees
 * which car it is. Mirrors the backend `LiveMainCar` projection
 * (functions/src/live/live-core.ts) — only display-safe fields, never plates/VIN.
 * [imagePath] points into the owner's public-readable vehicleImages/ prefix; a
 * viewer resolves it to a URL lazily for rendering.
 */
data class LiveMainCar(
    val make: String,
    val model: String,
    val modelYear: Int,
    val imagePath: String? = null,
)

/**
 * A lean live marker read from `liveLocation/{uid}/latest` — enough to draw a
 * map pin. Mirrors the backend `buildLatestNode` shape
 * (functions/src/live/live-core.ts): latitude/longitude plus the denormalized
 * [displayName] and [mainCar]. [uid] is carried so callers can key markers and
 * colour the caller's own differently from other members'. Only members who are
 * actively sharing have a `latest` node, so a null flow value means "not sharing".
 */
data class LiveMarker(
    val uid: String,
    val latitude: Double,
    val longitude: Double,
    val displayName: String? = null,
    /** The sharer's main car at session start, or null when they have none. */
    val mainCar: LiveMainCar? = null,
    /**
     * When this sample was recorded, ISO-8601, as written by the publisher, or
     * null when the node predates the field / it is unreadable.
     *
     * Carried so a viewer can tell a live position from a position that has
     * simply stopped updating. A `latest` node is removed when a session stops
     * or expires, but a device that loses signal mid-drive leaves its last
     * sample sitting there looking current — and the convoy direction arrows
     * would then point confidently at where somebody used to be. Consumers
     * decide the window (see
     * [com.kungsbackacarcommunity.app.map.ConvoyArrowPlanner.STALE_AFTER_MS]);
     * null means "unknown", which is deliberately NOT treated as stale.
     */
    val recordedAtIso: String? = null,
)

/**
 * One nearby STANDALONE live sharer, as `live.listNearby` returns them. The
 * viewer uses [uid] to subscribe the existing per-uid [LiveLocationRepository.observeLatest]
 * stream (the live source of truth); [latitude]/[longitude]/[displayName] seed
 * the initial marker before the first RTDB frame arrives. Mirrors the backend
 * `LiveNearbyView` (functions/src/live/nearby-core.ts).
 */
data class NearbyLiveSession(
    val uid: String,
    val latitude: Double,
    val longitude: Double,
    val displayName: String? = null,
)

/**
 * Live-location session operations (Phase 12 slice 5). Firebase-free interface
 * so the coordinator and screen logic are JVM-unit-testable with fakes.
 *
 * Session state and markers live in Realtime Database under liveLocation/; all
 * writes flow through the member-gated callables (functions/src/live/session.ts).
 *
 * Session state is observed per-owner ([observeOwnSession]); live markers are
 * read per-uid ([observeLatest]) from `liveLocation/{uid}/latest`. There is NO
 * collection scan — the RTDB rules grant only per-uid reads — so viewing other
 * members' markers is done by combining explicit per-uid [observeLatest] flows
 * (a group-drive/convoy roster, or the [listNearby] discovery result) in the
 * Map layer.
 */
interface LiveLocationRepository {
    /** live.startSession — (re)starts the caller's session with a duration. */
    suspend fun startSession(duration: LiveSessionDuration)

    /** live.updatePosition — publishes one sample (requires an active session). */
    suspend fun updatePosition(coordinate: LiveCoordinate)

    /** live.stopSession — stops sharing and removes the marker immediately. */
    suspend fun stopSession()

    /**
     * live.extendSession — the user's "yes, keep sharing" answer to the pre-expiry
     * prompt. Pushes the active session's expiry to a fresh server-capped window
     * (never past the 6h cap). No-op-shaped for the caller: it either succeeds or
     * throws (e.g. the session already expired, in which case the stop path wins).
     */
    suspend fun extendSession()

    /** live.hideMeNow — privacy stop; always available, even while suspended. */
    suspend fun hideMeNow()

    /**
     * live.listNearby — active standalone sharers within [radiusMeters] of
     * [center], EXCLUDING the caller and blocked relationships (enforced
     * server-side). Returns the discovery seeds; the caller subscribes each
     * uid's [observeLatest] for live updates. A bounded, one-round-trip geo
     * query (mirrors incidents.listNearby) — never a collection scan.
     */
    suspend fun listNearby(
        center: LatLng,
        radiusMeters: Double = DEFAULT_NEARBY_RADIUS_METERS,
    ): List<NearbyLiveSession>

    /** Live view of the caller's own session node; emits null when none. */
    fun observeOwnSession(uid: String): Flow<LiveSessionInfo?>

    /**
     * Live view of a single member's latest marker at
     * `liveLocation/{uid}/latest`. Emits null when the member is not sharing
     * (absent node) or when the read is denied (not an active, non-suspended
     * member) — never scans the collection. Used for both the caller's own
     * marker and, combined per-uid, other members'.
     */
    fun observeLatest(uid: String): Flow<LiveMarker?>
}
