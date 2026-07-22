package com.kungsbackacarcommunity.app.live

/**
 * Maps the raw `live.listNearby` callable payload into [NearbyLiveSession]s.
 *
 * Split out of [FirebaseLiveLocationRepository] so the SDK→model mapping is
 * unit-testable off-device (the callable SDK hands back nested
 * `Map`/`List`/`Number` structures). Defensive throughout: a malformed row is
 * dropped rather than failing the whole batch — one bad sharer must not blank
 * out everyone else on the map, exactly as the incidents parser degrades.
 */
object NearbyLiveParser {

    /** Parses `{ sessions: [{ uid, latitude, longitude, displayName }] }`. */
    fun parse(data: Any?): List<NearbyLiveSession> {
        val root = data as? Map<*, *> ?: return emptyList()
        val sessions = root["sessions"] as? List<*> ?: return emptyList()
        return sessions.mapNotNull { parseSession(it) }
    }

    private fun parseSession(raw: Any?): NearbyLiveSession? {
        val row = raw as? Map<*, *> ?: return null
        val uid = (row["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val latitude = (row["latitude"] as? Number)?.toDouble() ?: return null
        val longitude = (row["longitude"] as? Number)?.toDouble() ?: return null
        val displayName = (row["displayName"] as? String)?.takeIf { it.isNotBlank() }
        return NearbyLiveSession(
            uid = uid,
            latitude = latitude,
            longitude = longitude,
            displayName = displayName,
        )
    }
}
