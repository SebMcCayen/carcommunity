package com.kungsbackacarcommunity.app.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RecentSearchesStore] backed by [android.content.SharedPreferences], the app's
 * lightweight key/value persistence for small local state. Persists the last
 * [RecentSearches.MAX] selected places as a compact JSON array so they survive
 * process death and reappear in the search bar's empty state.
 *
 * The list logic on *write* (promote-to-front, de-duplicate, cap) is the pure,
 * unit-tested [RecentSearches]. On *read*, [decode] does more than parse: a
 * stored payload is untrusted input, so it also skips entries that would decode
 * to an unusable place and re-applies the cap. Both are pinned by
 * [PrefsRecentSearchesStoreTest].
 *
 * Every access is wrapped defensively, so a bad write can never crash the search
 * UI: an absent or unparseable payload degrades to an empty list, and one whose
 * individual entries are corrupt degrades to whichever entries survive [decode].
 */
class PrefsRecentSearchesStore(context: Context) : RecentSearchesStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun recent(): List<PlaceSuggestion> =
        runCatching { decode(prefs.getString(KEY_RECENT, null)) }.getOrDefault(emptyList())

    override fun record(place: PlaceSuggestion) {
        val next = RecentSearches.record(recent(), place)
        runCatching { prefs.edit().putString(KEY_RECENT, encode(next)).apply() }
    }

    /**
     * `internal` rather than private purely so the pure (de)serialization can be
     * unit-tested: the store itself needs a [Context] for SharedPreferences and
     * so cannot be constructed in a JVM test, but [decode] is where a corrupt or
     * oversized payload is disarmed and is worth pinning directly.
     */
    internal companion object {
        const val PREFS_NAME = "nav_recent_searches"
        const val KEY_RECENT = "recent"

        fun encode(items: List<PlaceSuggestion>): String {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("address", item.address ?: JSONObject.NULL)
                        .put("lng", item.point.longitude)
                        .put("lat", item.point.latitude),
                )
            }
            return array.toString()
        }

        fun decode(raw: String?): List<PlaceSuggestion> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = JSONArray(raw)
            val out = ArrayList<PlaceSuggestion>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                // Require present, finite, in-range coordinates. optDouble defaults
                // missing/non-numeric values to 0.0, which would silently surface a
                // bogus recent at (0,0) that could then be routed/navigated to — so
                // a partially corrupt entry is skipped instead.
                val lng = obj.optDouble("lng", Double.NaN)
                val lat = obj.optDouble("lat", Double.NaN)
                if (!lng.isFinite() || !lat.isFinite() ||
                    lng !in -180.0..180.0 || lat !in -90.0..90.0
                ) {
                    continue
                }
                val address = obj.optString("address").takeIf { it.isNotBlank() && !obj.isNull("address") }
                out.add(
                    PlaceSuggestion(
                        id = obj.optString("id").ifBlank { "$i-$name" },
                        name = name,
                        address = address,
                        point = LatLng(longitude = lng, latitude = lat),
                    ),
                )
            }
            // Enforce the store contract that recents are capped, even if a
            // corrupt/oversized SharedPreferences payload holds more than
            // [RecentSearches.MAX] entries, so callers relying on the cap can't
            // be handed an unbounded list. This also carries an existing user
            // down from a payload written under a larger historical cap.
            //
            // take (not takeLast): recents are stored most-recent-first, so the
            // front of the list is what the user must keep.
            return out.take(RecentSearches.MAX)
        }
    }
}
