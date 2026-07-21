package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SavedPlacesManager] — the management operations behind the
 * standalone Saved-places screen, exercised as pure logic over an in-memory
 * [SavedPlacesStore]. The invariants pinned here are the ones the screen relies
 * on: rename touches only its own entry (and only a favourite's visible name),
 * delete removes exactly one entry, and neither ever duplicates or strands a
 * singleton. The "change address" flow's kernel (re-locating a singleton keeps
 * it single) is pinned too, documenting what the picker's save path guarantees.
 */
class SavedPlacesManagerTest {
    private fun place(id: String, lng: Double = 1.0, lat: Double = 2.0) =
        PlaceSuggestion(id = id, name = "Place $id", address = "Addr $id", point = LatLng(lng, lat))

    private fun home(id: String = "h") =
        SavedPlaces.create(SavedPlaceKind.Home, place(id, lng = 10.0), "ignored")

    private fun work(id: String = "w") =
        SavedPlaces.create(SavedPlaceKind.Work, place(id, lng = 20.0), "ignored")

    private fun favourite(id: String, label: String = "Fav $id") =
        SavedPlaces.create(SavedPlaceKind.Favourite, place(id, lng = id.hashCode().toDouble() % 90), label)

    private fun manager(vararg initial: SavedPlace) =
        SavedPlacesManager(InMemorySavedPlacesStore(initial.toList()))

    @Test
    fun `places returns the store's ordered list`() {
        val m = manager(favourite("a"), home(), work())
        val kinds = m.places().map { it.kind }
        // sort() puts Home, then Work, then favourites regardless of insert order.
        assertEquals(listOf(SavedPlaceKind.Home, SavedPlaceKind.Work, SavedPlaceKind.Favourite), kinds)
    }

    @Test
    fun `rename changes a favourite's label in place and touches nothing else`() {
        val m = manager(favourite("a", "First"), favourite("b", "Second"))
        val target = m.places().single { it.place.id == "a" }
        m.rename(target, "Renamed")

        val after = m.places()
        assertEquals(2, after.size)
        assertEquals("Renamed", after.single { it.place.id == "a" }.label)
        // The other favourite is untouched...
        assertEquals("Second", after.single { it.place.id == "b" }.label)
        // ...and the renamed one kept its id and slot (no move to the end).
        assertEquals("fav:a", after.first().id)
        assertEquals("a", after.first().place.id)
    }

    @Test
    fun `rename never duplicates the entry`() {
        val m = manager(favourite("a", "First"))
        m.rename(m.places().single(), "Renamed")
        assertEquals(1, m.places().size)
    }

    @Test
    fun `rename truncates an over-long label to the store maximum`() {
        val m = manager(favourite("a"))
        val tooLong = "x".repeat(SavedPlaces.MAX_LABEL + 20)
        m.rename(m.places().single(), tooLong)
        assertEquals(SavedPlaces.MAX_LABEL, m.places().single().label.length)
    }

    @Test
    fun `rename with a blank label falls back to the place name`() {
        val m = manager(favourite("a", "Old"))
        m.rename(m.places().single(), "   ")
        assertEquals("Place a", m.places().single().label)
    }

    @Test
    fun `renaming a singleton keeps it a single entry at the same location`() {
        // Home ignores its label for display, but the operation stays kind-agnostic
        // and must not duplicate or move the singleton.
        val m = manager(home("h1"))
        m.rename(m.places().single(), "whatever")
        val homes = m.places().filter { it.kind == SavedPlaceKind.Home }
        assertEquals(1, homes.size)
        assertEquals("home", homes.single().id)
        assertEquals("h1", homes.single().place.id)
    }

    @Test
    fun `delete removes one favourite and leaves the rest`() {
        val m = manager(favourite("a"), favourite("b"))
        val target = m.places().single { it.place.id == "a" }
        m.delete(target.id)

        val after = m.places()
        assertEquals(1, after.size)
        assertEquals("b", after.single().place.id)
    }

    @Test
    fun `delete of Home leaves Work and favourites intact`() {
        val m = manager(home(), work(), favourite("a"))
        m.delete("home")

        val after = m.places()
        assertFalse(after.any { it.kind == SavedPlaceKind.Home })
        assertTrue(after.any { it.kind == SavedPlaceKind.Work })
        assertTrue(after.any { it.place.id == "a" })
        assertEquals(2, after.size)
    }

    @Test
    fun `delete of an unknown id is a no-op`() {
        val m = manager(favourite("a"))
        m.delete("fav:does-not-exist")
        assertEquals(1, m.places().size)
    }

    @Test
    fun `re-locating Home via the store keeps exactly one Home at the new place`() {
        // The "change address" flow ends in the picker saving the picked place
        // under the same kind. For a singleton that is create()+upsert on the same
        // id ("home"), so the old Home is replaced, not accumulated.
        val store = InMemorySavedPlacesStore(listOf(home("old")))
        store.save(SavedPlaces.create(SavedPlaceKind.Home, place("new", lng = 30.0), "ignored"))

        val homes = store.saved().filter { it.kind == SavedPlaceKind.Home }
        assertEquals(1, homes.size)
        assertEquals("new", homes.single().place.id)
        // And nothing else crept in.
        assertNull(store.saved().firstOrNull { it.place.id == "old" })
    }
}
