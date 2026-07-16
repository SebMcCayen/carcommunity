package com.kungsbackacarcommunity.app.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [SavedPlacesStore] backed by [android.content.SharedPreferences], mirroring
 * [PrefsRecentSearchesStore]: a compact JSON array of the user's saved places,
 * read and written locally so the shortcuts render instantly and work with no
 * network — the whole point of a "one-tap, offline-friendly" favourite.
 *
 * Unlike recents, the payload is keyed **per uid**: saved places are a curated,
 * personal list, so two accounts sharing a device must not see each other's
 * Home. The uid only namespaces the local key — nothing is uploaded (see the
 * cloud-sync note below).
 *
 * The list logic (upsert, singleton Home/Work, ordering, cap) is the pure,
 * unit-tested [SavedPlaces]; this class only adds JSON (de)serialization and the
 * prefs read/write glue. Every access is wrapped defensively: a corrupt/absent
 * payload degrades to an empty list rather than throwing, so a bad write can
 * never crash the search UI.
 *
 * Device-local by design (Android lane, no backend/rules change). Follow-up:
 * cloud-syncing saved places per uid so they follow the user to a new phone —
 * today a reinstall or a new device starts empty.
 */
class PrefsSavedPlacesStore(
    context: Context,
    private val uid: String,
) : SavedPlacesStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val key = keyFor(uid)

    override fun saved(): List<SavedPlace> =
        runCatching { decode(prefs.getString(key, null)) }.getOrDefault(emptyList())

    override fun save(place: SavedPlace) = write(SavedPlaces.upsert(saved(), place))

    override fun remove(id: String) = write(SavedPlaces.remove(saved(), id))

    private fun write(items: List<SavedPlace>) {
        runCatching { prefs.edit().putString(key, encode(items)).apply() }
    }

    private companion object {
        const val PREFS_NAME = "nav_saved_places"

        /** Namespaces the payload per account (see the class doc). */
        fun keyFor(uid: String): String = "saved:${uid.ifBlank { "anon" }}"

        fun encode(items: List<SavedPlace>): String {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("kind", item.kind.name)
                        .put("label", item.label)
                        .put("name", item.place.name)
                        .put("address", item.place.address ?: JSONObject.NULL)
                        .put("lng", item.place.point.longitude)
                        .put("lat", item.place.point.latitude)
                        .put("placeId", item.place.id),
                )
            }
            return array.toString()
        }

        fun decode(raw: String?): List<SavedPlace> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = JSONArray(raw)
            val out = ArrayList<SavedPlace>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                // Require present, finite, in-range coordinates. optDouble defaults
                // missing/non-numeric values to 0.0, which would silently surface a
                // saved place at (0,0) that could then be routed/navigated to — so
                // a partially corrupt entry is skipped instead (as in recents).
                val lng = obj.optDouble("lng", Double.NaN)
                val lat = obj.optDouble("lat", Double.NaN)
                if (!lng.isFinite() || !lat.isFinite() ||
                    lng !in -180.0..180.0 || lat !in -90.0..90.0
                ) {
                    continue
                }
                // An unknown/absent kind (a payload from a newer build, or a
                // corrupt one) degrades to Favourite rather than dropping the
                // entry: the user still sees the place they saved, just without
                // its shortcut slot.
                val kind =
                    SavedPlaceKind.entries.firstOrNull { it.name == obj.optString("kind") }
                        ?: SavedPlaceKind.Favourite
                val address =
                    obj.optString("address").takeIf { it.isNotBlank() && !obj.isNull("address") }
                val place =
                    PlaceSuggestion(
                        id = obj.optString("placeId"),
                        name = name,
                        address = address,
                        point = LatLng(longitude = lng, latitude = lat),
                    )
                val label = SavedPlaces.normalizeLabel(obj.optString("label")).ifBlank { name }
                out.add(
                    SavedPlace(
                        id = obj.optString("id").ifBlank { SavedPlaces.idFor(kind, place) },
                        kind = kind,
                        label = label,
                        place = place,
                    ),
                )
            }
            // Enforce the store contract (ordered, capped, one Home/one Work) even
            // if a corrupt or oversized payload violates it, so callers can trust
            // the invariants they were promised. distinctBy drops any duplicate id
            // — including a second "home" — keeping the first occurrence.
            return SavedPlaces.sort(out.distinctBy { it.id }).take(SavedPlaces.MAX)
        }
    }
}
