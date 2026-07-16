package com.kungsbackacarcommunity.app.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
    fun `decode caps an oversized payload by dropping the oldest favourites`() {
        // The size assertion above cannot tell WHICH end was dropped. An oversized
        // payload must lose its oldest favourites, matching upsert's eviction.
        val entries = (1..SavedPlaces.MAX + 2).map {
            entry(id = "fav:p$it", kind = "Favourite", lng = it.toDouble())
        }
        val decoded = PrefsSavedPlacesStore.decode(payload(*entries.toTypedArray()))
        assertEquals((3..SavedPlaces.MAX + 2).map { "fav:p$it" }, decoded.map { it.id })
    }

    @Test
    fun `a payload saved under the old cap of 12 keeps Home, Work and the newest favourites`() {
        // The real migration: MAX was lowered from 12 to 6 (saved == visible), so
        // an existing user's stored payload can hold 12 entries. 12 is written out
        // literally rather than derived from SavedPlaces.MAX — it is a historical
        // value this payload was written under, not today's cap, and tying it to
        // MAX would make the test tautological and silently change meaning if the
        // cap moves again.
        val legacy = listOf(entry(id = "home", kind = "Home", lng = 80.0)) +
            listOf(entry(id = "work", kind = "Work", lng = 81.0)) +
            (1..10).map { entry(id = "fav:p$it", kind = "Favourite", lng = it.toDouble()) }
        assertEquals(12, legacy.size)

        val decoded = PrefsSavedPlacesStore.decode(payload(*legacy.toTypedArray()))

        // Trimmed to today's cap, and the user keeps what they'd want kept:
        // both singletons plus their four MOST RECENT favourites. The six oldest
        // favourites are what's lost — never Home/Work, never the newest.
        assertEquals(SavedPlaces.MAX, decoded.size)
        assertEquals(listOf("home", "work", "fav:p7", "fav:p8", "fav:p9", "fav:p10"), decoded.map { it.id })
    }

    @Test
    fun `keyFor namespaces the payload per uid`() {
        assertEquals("saved:u1", PrefsSavedPlacesStore.keyFor("u1"))
        assertNotEquals(PrefsSavedPlacesStore.keyFor("u1"), PrefsSavedPlacesStore.keyFor("u2"))
    }

    @Test
    fun `a blank uid is rejected rather than sharing a namespace`() {
        // Fail CLOSED. A shared fallback bucket would let two accounts on one
        // device read each other's Home/Work addresses — the exact leak the
        // per-uid key exists to prevent. Blank is unreachable in production (the
        // uid comes from a Firebase session), so throwing surfaces the bug in
        // test/preview instead of silently merging two users' saved places.
        assertThrows(IllegalArgumentException::class.java) { PrefsSavedPlacesStore.keyFor("") }
        assertThrows(IllegalArgumentException::class.java) { PrefsSavedPlacesStore.keyFor("   ") }
    }

    @Test
    fun `a corrupt or absent payload degrades to an empty list`() {
        assertEquals(emptyList<SavedPlace>(), PrefsSavedPlacesStore.decode(null))
        assertEquals(emptyList<SavedPlace>(), PrefsSavedPlacesStore.decode(""))
    }
}
