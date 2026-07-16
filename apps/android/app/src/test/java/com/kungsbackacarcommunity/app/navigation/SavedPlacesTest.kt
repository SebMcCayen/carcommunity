package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure saved-places logic ([SavedPlaces]) and the in-memory
 * store. The invariants asserted here are the ones the UI relies on: Home/Work
 * are singletons, the ordering is stable, re-saving never duplicates, and the
 * cap can never evict a deliberately-set Home/Work.
 */
class SavedPlacesTest {
    private fun place(id: String, lng: Double = 0.0, lat: Double = 0.0) =
        PlaceSuggestion(id = id, name = "Place $id", address = "Addr $id", point = LatLng(lng, lat))

    private fun favourite(id: String, label: String = "Fav $id") =
        SavedPlaces.create(SavedPlaceKind.Favourite, place(id, lng = id.hashCode().toDouble() % 90), label)

    @Test
    fun `singleton kinds get a kind-derived id so re-saving replaces`() {
        assertEquals("home", SavedPlaces.idFor(SavedPlaceKind.Home, place("a")))
        assertEquals("work", SavedPlaces.idFor(SavedPlaceKind.Work, place("a")))
        // A favourite keys off the underlying place instead.
        assertEquals("fav:a", SavedPlaces.idFor(SavedPlaceKind.Favourite, place("a")))
    }

    @Test
    fun `a favourite without a geocoder id falls back to its coordinate`() {
        val pin = PlaceSuggestion(id = "", name = "Pin", address = null, point = LatLng(1.5, 2.5))
        assertEquals("fav:1.5,2.5", SavedPlaces.idFor(SavedPlaceKind.Favourite, pin))
    }

    @Test
    fun `saving a new Home replaces the old one rather than adding a second`() {
        val first = SavedPlaces.create(SavedPlaceKind.Home, place("a"), "ignored")
        val moved = SavedPlaces.create(SavedPlaceKind.Home, place("b"), "ignored")
        val result = SavedPlaces.upsert(SavedPlaces.upsert(emptyList(), first), moved)
        assertEquals(1, result.size)
        assertEquals("b", result.single().place.id)
    }

    @Test
    fun `re-saving the same favourite updates its label in place`() {
        val original = favourite("a", label = "Old")
        val renamed = favourite("a", label = "New")
        val result = SavedPlaces.upsert(listOf(original), renamed)
        assertEquals(1, result.size)
        assertEquals("New", result.single().label)
    }

    @Test
    fun `create trims and caps an over-long label`() {
        val long = "x".repeat(SavedPlaces.MAX_LABEL + 20)
        val saved = SavedPlaces.create(SavedPlaceKind.Favourite, place("a"), "   $long   ")
        assertEquals(SavedPlaces.MAX_LABEL, saved.label.length)
    }

    @Test
    fun `create falls back to the place name when the label is blank`() {
        val saved = SavedPlaces.create(SavedPlaceKind.Favourite, place("a"), "   ")
        assertEquals("Place a", saved.label)
    }

    @Test
    fun `sort puts Home first, then Work, then favourites in insertion order`() {
        val items =
            listOf(
                favourite("f1"),
                SavedPlaces.create(SavedPlaceKind.Work, place("w"), "w"),
                favourite("f2"),
                SavedPlaces.create(SavedPlaceKind.Home, place("h"), "h"),
            )
        val sorted = SavedPlaces.sort(items)
        assertEquals(
            listOf(SavedPlaceKind.Home, SavedPlaceKind.Work, SavedPlaceKind.Favourite, SavedPlaceKind.Favourite),
            sorted.map { it.kind },
        )
        // Favourites keep their relative order.
        assertEquals(listOf("Fav f1", "Fav f2"), sorted.filter { it.kind == SavedPlaceKind.Favourite }.map { it.label })
    }

    @Test
    fun `upsert caps the list by evicting the oldest favourite`() {
        val full = (1..SavedPlaces.MAX).fold(emptyList<SavedPlace>()) { acc, i -> SavedPlaces.upsert(acc, favourite("f$i")) }
        assertEquals(SavedPlaces.MAX, full.size)
        val fresh = favourite("new")
        val result = SavedPlaces.upsert(full, fresh)
        assertEquals(SavedPlaces.MAX, result.size)
        assertTrue(result.contains(fresh))
        // The OLDEST favourite (the first added) is the one dropped.
        assertTrue(result.none { it.id == "fav:f1" })
    }

    @Test
    fun `the cap never evicts Home or Work`() {
        // Fill to the cap with Home + Work + favourites, then add one more.
        val seeded =
            SavedPlaces.upsert(
                SavedPlaces.upsert(emptyList(), SavedPlaces.create(SavedPlaceKind.Home, place("h"), "h")),
                SavedPlaces.create(SavedPlaceKind.Work, place("w"), "w"),
            )
        val full =
            (1..SavedPlaces.MAX - 2).fold(seeded) { acc, i -> SavedPlaces.upsert(acc, favourite("f$i")) }
        assertEquals(SavedPlaces.MAX, full.size)

        val result = SavedPlaces.upsert(full, favourite("new"))
        assertEquals(SavedPlaces.MAX, result.size)
        assertEquals(1, result.count { it.kind == SavedPlaceKind.Home })
        assertEquals(1, result.count { it.kind == SavedPlaceKind.Work })
        assertTrue(result.none { it.id == "fav:f1" })
    }

    @Test
    fun `a full list of only Home and Work rejects a new entry rather than growing`() {
        // Degenerate guard: at the cap with no favourite to evict, the list must
        // not grow past max and must not silently drop a singleton.
        val atCap =
            listOf(
                SavedPlaces.create(SavedPlaceKind.Home, place("h"), "h"),
                SavedPlaces.create(SavedPlaceKind.Work, place("w"), "w"),
            )
        val result = SavedPlaces.upsert(atCap, favourite("new"), max = 2)
        assertEquals(atCap, result)
    }

    @Test
    fun `replacing an existing entry at the cap is allowed`() {
        val full = (1..SavedPlaces.MAX).fold(emptyList<SavedPlace>()) { acc, i -> SavedPlaces.upsert(acc, favourite("f$i")) }
        val renamed = favourite("f3", label = "Renamed")
        val result = SavedPlaces.upsert(full, renamed)
        assertEquals(SavedPlaces.MAX, result.size)
        assertEquals("Renamed", result.single { it.id == "fav:f3" }.label)
    }

    @Test
    fun `remove drops the entry and is a no-op for an unknown id`() {
        val items = listOf(favourite("a"), favourite("b"))
        assertEquals(1, SavedPlaces.remove(items, "fav:a").size)
        assertEquals(items, SavedPlaces.remove(items, "nope"))
    }

    @Test
    fun `find matches by geocoder id`() {
        val saved = listOf(SavedPlaces.create(SavedPlaceKind.Home, place("a"), "h"))
        // A different name/address for the same geocoder id still resolves.
        val same = place("a").copy(name = "Renamed by the geocoder")
        assertEquals(SavedPlaceKind.Home, SavedPlaces.find(saved, same)?.kind)
        assertNull(SavedPlaces.find(saved, place("b", lng = 9.0)))
    }

    @Test
    fun `find falls back to coordinate matching when ids are blank`() {
        val pin = PlaceSuggestion(id = "", name = "Pin", address = null, point = LatLng(1.0, 2.0))
        val saved = listOf(SavedPlaces.create(SavedPlaceKind.Favourite, pin, "Spot"))
        val samePin = pin.copy(name = "Pressed again")
        assertEquals("Spot", SavedPlaces.find(saved, samePin)?.label)
    }

    @Test
    fun `in-memory store saves, replaces, orders and removes`() {
        val store = InMemorySavedPlacesStore()
        store.save(favourite("a"))
        store.save(SavedPlaces.create(SavedPlaceKind.Home, place("h"), "h"))
        // Home sorts to the front even though it was saved second.
        assertEquals(SavedPlaceKind.Home, store.saved().first().kind)

        store.save(favourite("a", label = "Renamed"))
        assertEquals(2, store.saved().size)
        assertEquals("Renamed", store.saved().single { it.id == "fav:a" }.label)

        store.remove("home")
        assertEquals(listOf("fav:a"), store.saved().map { it.id })
    }

    @Test
    fun `in-memory store caps its seeded list`() {
        val seed = (1..SavedPlaces.MAX + 3).map { favourite("f$it") }
        assertEquals(SavedPlaces.MAX, InMemorySavedPlacesStore(seed).saved().size)
    }
}
