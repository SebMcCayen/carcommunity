package com.kungsbackacarcommunity.app.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure JSON (de)serialization half of [PrefsSavedPlacesStore].
 *
 * The prefs/Context half cannot be exercised in a JVM unit test, but [decode] is
 * the point where an untrusted, possibly-corrupt payload is turned back into the
 * invariants the UI relies on — so it is pinned here directly.
 */
class PrefsSavedPlacesStoreTest {
    private fun entry(
        id: String,
        kind: String,
        name: String = "Place $id",
        lng: Double = 1.0,
        lat: Double = 2.0,
    ) = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("label", "Label $id")
        .put("name", name)
        .put("address", JSONObject.NULL)
        .put("lng", lng)
        .put("lat", lat)
        .put("placeId", "p$id")

    private fun payload(vararg entries: JSONObject): String =
        JSONArray().apply { entries.forEach { put(it) } }.toString()

    @Test
    fun `a corrupt Home id cannot smuggle in a second Home`() {
        // A payload where one Home carries a non-canonical id. The stored id is
        // untrusted input: distinctBy alone would see two distinct ids and keep
        // both, yielding two Home rows.
        val raw = payload(
            entry(id = "home", kind = "Home", name = "Real home"),
            entry(id = "not-home", kind = "Home", name = "Smuggled home", lng = 5.0),
        )
        val decoded = PrefsSavedPlacesStore.decode(raw)
        assertEquals(1, decoded.count { it.kind == SavedPlaceKind.Home })
        // The id is derived from the kind, never from the payload.
        assertEquals("home", decoded.single { it.kind == SavedPlaceKind.Home }.id)
    }

    @Test
    fun `a corrupt Work id cannot smuggle in a second Work`() {
        val raw = payload(
            entry(id = "work", kind = "Work", name = "Real work"),
            entry(id = "somethingelse", kind = "Work", name = "Smuggled work", lng = 5.0),
        )
        val decoded = PrefsSavedPlacesStore.decode(raw)
        assertEquals(1, decoded.count { it.kind == SavedPlaceKind.Work })
        assertEquals("work", decoded.single { it.kind == SavedPlaceKind.Work }.id)
    }

    @Test
    fun `the first occurrence of a duplicated singleton wins`() {
        val raw = payload(
            entry(id = "home", kind = "Home", name = "First"),
            entry(id = "not-home", kind = "Home", name = "Second", lng = 5.0),
        )
        val decoded = PrefsSavedPlacesStore.decode(raw)
        assertEquals("First", decoded.single { it.kind == SavedPlaceKind.Home }.place.name)
    }

    @Test
    fun `favourites keep their own ids rather than being canonicalized`() {
        val raw = payload(
            entry(id = "fav:pa", kind = "Favourite"),
            entry(id = "fav:pb", kind = "Favourite", lng = 5.0),
        )
        val decoded = PrefsSavedPlacesStore.decode(raw)
        assertEquals(listOf("fav:pa", "fav:pb"), decoded.map { it.id })
    }

    @Test
    fun `a blank id still falls back to the derived id`() {
        // The helper derives placeId from id, so a blank id means placeId "p".
        val raw = payload(entry(id = "", kind = "Favourite"))
        assertEquals(listOf("fav:p"), PrefsSavedPlacesStore.decode(raw).map { it.id })
    }

    @Test
    fun `decode still orders Home first, then Work, then favourites`() {
        val raw = payload(
            entry(id = "fav:pa", kind = "Favourite"),
            entry(id = "work", kind = "Work", lng = 3.0),
            entry(id = "home", kind = "Home", lng = 4.0),
        )
        assertEquals(
            listOf(SavedPlaceKind.Home, SavedPlaceKind.Work, SavedPlaceKind.Favourite),
            PrefsSavedPlacesStore.decode(raw).map { it.kind },
        )
    }

    @Test
    fun `decode caps an oversized payload`() {
        val entries = (1..SavedPlaces.MAX + 5).map {
            entry(id = "fav:p$it", kind = "Favourite", lng = it.toDouble())
        }
        assertEquals(SavedPlaces.MAX, PrefsSavedPlacesStore.decode(payload(*entries.toTypedArray())).size)
    }

    @Test
    fun `a corrupt or absent payload degrades to an empty list`() {
        assertEquals(emptyList<SavedPlace>(), PrefsSavedPlacesStore.decode(null))
        assertEquals(emptyList<SavedPlace>(), PrefsSavedPlacesStore.decode(""))
    }
}
