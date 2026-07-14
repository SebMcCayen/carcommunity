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
 * The list logic (promote-to-front, de-duplicate, cap) is the pure, unit-tested
 * [RecentSearches]; this class only adds the JSON (de)serialization and the
 * SharedPreferences read/write glue, verified on device. Every access is wrapped
 * defensively: a corrupt/absent payload degrades to an empty list rather than
 * throwing, so a bad write can never crash the search UI.
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

    private companion object {
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
                val address = obj.optString("address").takeIf { it.isNotBlank() && !obj.isNull("address") }
                out.add(
                    PlaceSuggestion(
                        id = obj.optString("id").ifBlank { "$i-$name" },
                        name = name,
                        address = address,
                        point = LatLng(longitude = obj.optDouble("lng"), latitude = obj.optDouble("lat")),
                    ),
                )
            }
            return out
        }
    }
}
