package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    private fun place(id: String, lng: Double = 0.0, lat: Double = 0.0) =
        PlaceSuggestion(id = id, name = "Place $id", address = null, point = LatLng(lng, lat))

    @Test
    fun `record promotes a new place to the front`() {
        val a = place("a")
        val b = place("b")
        val result = RecentSearches.record(listOf(a), b)
        assertEquals(listOf(b, a), result)
    }

    @Test
    fun `re-recording a place de-duplicates and moves it to the front`() {
        val a = place("a")
        val b = place("b")
        val c = place("c")
        val result = RecentSearches.record(listOf(a, b, c), b)
        assertEquals(listOf(b, a, c), result)
    }

    @Test
    fun `record caps the list to max, dropping the oldest`() {
        val existing = (1..RecentSearches.MAX).map { place("p$it") }
        val fresh = place("new")
        val result = RecentSearches.record(existing, fresh)
        assertEquals(RecentSearches.MAX, result.size)
        assertEquals(fresh, result.first())
        // The oldest (last) prior entry is dropped once the cap is exceeded.
        assertEquals(false, result.contains(existing.last()))
    }

    @Test
    fun `blank ids fall back to coordinate matching for de-duplication`() {
        val first = PlaceSuggestion(id = "", name = "X", address = null, point = LatLng(1.0, 2.0))
        val again = PlaceSuggestion(id = "", name = "X again", address = null, point = LatLng(1.0, 2.0))
        val result = RecentSearches.record(listOf(first), again)
        assertEquals(listOf(again), result)
    }

    @Test
    fun `in-memory store records and caps`() {
        val store = InMemoryRecentSearchesStore()
        (1..RecentSearches.MAX + 2).forEach { store.record(place("p$it")) }
        assertEquals(RecentSearches.MAX, store.recent().size)
        assertEquals("p${RecentSearches.MAX + 2}", store.recent().first().id)
    }
}
