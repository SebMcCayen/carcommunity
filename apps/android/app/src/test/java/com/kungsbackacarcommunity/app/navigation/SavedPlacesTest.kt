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
    fun `re-saving a favourite keeps its position rather than moving it to the end`() {
        val saved =
            listOf("a", "b", "c").fold(emptyList<SavedPlace>()) { acc, id -> SavedPlaces.upsert(acc, favourite(id)) }
        assertEquals(listOf("fav:a", "fav:b", "fav:c"), saved.map { it.id })

        val result = SavedPlaces.upsert(saved, favourite("b", label = "Renamed"))
        // The rename must not reorder the list: b stays in the middle.
        assertEquals(listOf("fav:a", "fav:b", "fav:c"), result.map { it.id })
        assertEquals("Renamed", result.single { it.id == "fav:b" }.label)
    }

    @Test
    fun `re-saving a favourite does not change which one the cap evicts`() {
        val full = (1..SavedPlaces.MAX).fold(emptyList<SavedPlace>()) { acc, i -> SavedPlaces.upsert(acc, favourite("f$i")) }
        // Renaming the oldest favourite must not make it look like the newest.
        val renamed = SavedPlaces.upsert(full, favourite("f1", label = "Renamed"))
        val result = SavedPlaces.upsert(renamed, favourite("new"))
        assertEquals(SavedPlaces.MAX, result.size)
        // f1 is still the oldest, so it is still the one evicted.
        assertTrue(result.none { it.id == "fav:f1" })
        assertTrue(result.any { it.id == "fav:f2" })
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

    @Test
    fun `normalize re-derives singleton ids so a mislabelled Home cannot duplicate`() {
        val items =
            listOf(
                SavedPlace(id = "home", kind = SavedPlaceKind.Home, label = "First", place = place("h1")),
                SavedPlace(id = "not-home", kind = SavedPlaceKind.Home, label = "Second", place = place("h2", lng = 5.0)),
            )
        val result = SavedPlaces.normalize(items)
        assertEquals(1, result.count { it.kind == SavedPlaceKind.Home })
        assertEquals("home", result.single().id)
        // First occurrence wins, matching upsert's replace-in-place.
        assertEquals("First", result.single().label)
    }

    @Test
    fun `normalize leaves favourite ids alone`() {
        val items = listOf(favourite("a"), favourite("b"))
        assertEquals(listOf("fav:a", "fav:b"), SavedPlaces.normalize(items).map { it.id })
    }

    @Test
    fun `normalize keeps Home and Work when capping an oversized list`() {
        // Sort-then-cap, so the singletons cannot be pushed out by favourites
        // that happened to be listed ahead of them.
        val seed =
            (1..SavedPlaces.MAX + 3).map { favourite("f$it") } +
                SavedPlaces.create(SavedPlaceKind.Home, place("h", lng = 80.0), "h")
        val result = SavedPlaces.normalize(seed)
        assertEquals(SavedPlaces.MAX, result.size)
        assertEquals(1, result.count { it.kind == SavedPlaceKind.Home })
        assertEquals(SavedPlaceKind.Home, result.first().kind)
    }

    @Test
    fun `normalize caps by dropping the OLDEST favourites, as upsert's eviction does`() {
        // Favourites are held oldest-first, and upsert evicts the oldest at the
        // cap. An oversized seed (a downgrade from a build with a higher MAX, or a
        // corrupt payload) must lose the same end — otherwise the user silently
        // keeps their stalest favourites and loses their most recent ones.
        val seed = (1..SavedPlaces.MAX + 2).map { favourite("f$it") }
        val result = SavedPlaces.normalize(seed)
        assertEquals(SavedPlaces.MAX, result.size)
        // The two oldest go; the newest survives.
        assertTrue(result.none { it.id == "fav:f1" })
        assertTrue(result.none { it.id == "fav:f2" })
        assertTrue(result.any { it.id == "fav:f${SavedPlaces.MAX + 2}" })
        // Survivors stay oldest-first, so the next cap still evicts correctly.
        assertEquals((3..SavedPlaces.MAX + 2).map { "fav:f$it" }, result.map { it.id })
    }

    @Test
    fun `normalize exempts Home and Work from the cap and keeps the newest favourites`() {
        // MAX + 2 entries: the singletons must survive (they are never evicted),
        // which costs the two oldest favourites their slots.
        val seed =
            listOf(
                SavedPlaces.create(SavedPlaceKind.Home, place("h", lng = 80.0), "h"),
                SavedPlaces.create(SavedPlaceKind.Work, place("w", lng = 81.0), "w"),
            ) + (1..SavedPlaces.MAX).map { favourite("f$it") }
        val result = SavedPlaces.normalize(seed)
        assertEquals(SavedPlaces.MAX, result.size)
        assertEquals(1, result.count { it.kind == SavedPlaceKind.Home })
        assertEquals(1, result.count { it.kind == SavedPlaceKind.Work })
        assertTrue(result.none { it.id == "fav:f1" })
        assertTrue(result.none { it.id == "fav:f2" })
        assertEquals(
            listOf("home", "work") + (3..SavedPlaces.MAX).map { "fav:f$it" },
            result.map { it.id },
        )
    }

    @Test
    fun `in-memory store normalizes a seed holding two Homes`() {
        val store =
            InMemorySavedPlacesStore(
                listOf(
                    SavedPlace(id = "home", kind = SavedPlaceKind.Home, label = "First", place = place("h1")),
                    SavedPlace(id = "bogus", kind = SavedPlaceKind.Home, label = "Second", place = place("h2", lng = 5.0)),
                ),
            )
        assertEquals(1, store.saved().count { it.kind == SavedPlaceKind.Home })
    }

    // --- "Change address" re-point: kind/label defaults + stale-row sweep ------

    @Test
    fun `save defaults to a fresh Favourite named after the place with no context`() {
        val (kind, label) = SavedPlaces.resolveSaveDefaults(place("a"), existing = null, edit = null)
        assertEquals(SavedPlaceKind.Favourite, kind)
        assertEquals("Place a", label)
    }

    @Test
    fun `save defaults follow an existing match at this address`() {
        val existing = SavedPlaces.create(SavedPlaceKind.Work, place("a"), "ignored")
        val (kind, _) = SavedPlaces.resolveSaveDefaults(place("a"), existing = existing, edit = null)
        assertEquals(SavedPlaceKind.Work, kind)
    }

    @Test
    fun `a pending Home re-point pre-selects Home even for a brand-new address`() {
        val home = SavedPlaces.create(SavedPlaceKind.Home, place("old"), "old street")
        // The new address is not yet saved, so `existing` is null — the exact case
        // that used to default to Favourite and fork a second entry.
        val (kind, _) = SavedPlaces.resolveSaveDefaults(place("new"), existing = null, edit = SavedPlaces.editOf(home))
        assertEquals(SavedPlaceKind.Home, kind)
    }

    @Test
    fun `a pending favourite re-point keeps the favourite's label`() {
        val fav = favourite("old", label = "Mamma")
        val (kind, label) = SavedPlaces.resolveSaveDefaults(place("new"), existing = null, edit = SavedPlaces.editOf(fav))
        assertEquals(SavedPlaceKind.Favourite, kind)
        assertEquals("Mamma", label)
    }

    @Test
    fun `re-pointing Home strands nothing to sweep - its id is unchanged`() {
        val home = SavedPlaces.create(SavedPlaceKind.Home, place("old"), "old")
        val sweep = SavedPlaces.sweepIdFor(SavedPlaces.editOf(home), place("new"), SavedPlaceKind.Home)
        assertNull(sweep)
    }

    @Test
    fun `re-pointing a favourite to a new address sweeps its old id`() {
        val fav = favourite("old", label = "Mamma")
        val sweep = SavedPlaces.sweepIdFor(SavedPlaces.editOf(fav), place("new"), SavedPlaceKind.Favourite)
        assertEquals("fav:old", sweep)
    }

    @Test
    fun `converting a favourite into Home during the dialog sweeps the old favourite`() {
        val fav = favourite("old", label = "Mamma")
        // The user opened "Change address" on a favourite but picked Home in the
        // dialog: the new entry's id is "home", so the old "fav:old" must go.
        val sweep = SavedPlaces.sweepIdFor(SavedPlaces.editOf(fav), place("new"), SavedPlaceKind.Home)
        assertEquals("fav:old", sweep)
    }

    @Test
    fun `Change Home address updates Home in place rather than forking a Favourite`() {
        val store = InMemorySavedPlacesStore(listOf(SavedPlaces.create(SavedPlaceKind.Home, place("old"), "old")))
        val edit = SavedPlaces.editOf(store.saved().single { it.kind == SavedPlaceKind.Home })
        val newAddress = place("new", lng = 12.0)

        // Replay the picker's save: resolve the dialog defaults, sweep if needed,
        // then write through the same upsert the controller uses.
        val (kind, label) = SavedPlaces.resolveSaveDefaults(newAddress, existing = null, edit = edit)
        SavedPlaces.sweepIdFor(edit, newAddress, kind)?.let { store.remove(it) }
        store.save(SavedPlaces.create(kind, newAddress, label))

        val saved = store.saved()
        assertEquals(1, saved.size)
        val single = saved.single()
        assertEquals(SavedPlaceKind.Home, single.kind)
        assertEquals("home", single.id)
        assertEquals("new", single.place.id)
        assertTrue(saved.none { it.kind == SavedPlaceKind.Favourite })
    }

    @Test
    fun `Change favourite address moves the favourite instead of leaving a ghost`() {
        val store = InMemorySavedPlacesStore(listOf(favourite("old", label = "Mamma")))
        val edit = SavedPlaces.editOf(store.saved().single())
        val newAddress = place("new", lng = 20.0)

        val (kind, label) = SavedPlaces.resolveSaveDefaults(newAddress, existing = null, edit = edit)
        SavedPlaces.sweepIdFor(edit, newAddress, kind)?.let { store.remove(it) }
        store.save(SavedPlaces.create(kind, newAddress, label))

        val saved = store.saved()
        assertEquals(1, saved.size)
        assertEquals("fav:new", saved.single().id)
        assertEquals("Mamma", saved.single().label)
    }

    @Test
    fun `without the sweep a re-pointed favourite would strand its old row`() {
        // Guards the regression: skipping the sweep leaves BOTH the old and the new
        // favourite, which is exactly what threading the edit exists to prevent.
        val store = InMemorySavedPlacesStore(listOf(favourite("old", label = "Mamma")))
        store.save(SavedPlaces.create(SavedPlaceKind.Favourite, place("new", lng = 20.0), "Mamma"))
        assertEquals(2, store.saved().count { it.kind == SavedPlaceKind.Favourite })
    }

    @Test
    fun `a long-pressed pin round-trips into the same store nav-search reads`() {
        // Mirrors the map's "Save this location": a coordinate-only pin (no
        // geocoder id) saved as a Favourite through the SAME store the address
        // search reads/writes. It must land in saved() with a coordinate-derived
        // id and its exact point, so it re-opens as a one-tap destination.
        val store = InMemorySavedPlacesStore()
        val pin =
            PlaceSuggestion(
                id = "",
                name = "57.49102, 12.07660",
                address = null,
                point = LatLng(longitude = 12.0766, latitude = 57.49102),
            )

        store.save(SavedPlaces.create(SavedPlaceKind.Favourite, pin, label = "57.49102, 12.07660"))

        val saved = store.saved().single()
        assertEquals("fav:12.0766,57.49102", saved.id)
        assertEquals(LatLng(longitude = 12.0766, latitude = 57.49102), saved.place.point)
        assertEquals("57.49102, 12.07660", saved.label)
    }
}
