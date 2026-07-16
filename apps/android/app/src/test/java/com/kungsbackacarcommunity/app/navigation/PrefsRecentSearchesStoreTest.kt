package com.kungsbackacarcommunity.app.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure JSON (de)serialization half of [PrefsRecentSearchesStore].
 *
 * The prefs/Context half cannot be exercised in a JVM unit test, but [decode] is
 * the point where an untrusted, possibly-corrupt or oversized payload is turned
 * back into the invariants the UI relies on — so it is pinned here directly.
 */
class PrefsRecentSearchesStoreTest {
    private fun entry(
        id: String,
        name: String = "Place $id",
        lng: Double = 1.0,
        lat: Double = 2.0,
    ) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("address", JSONObject.NULL)
        .put("lng", lng)
        .put("lat", lat)

    private fun payload(vararg entries: JSONObject): String =
        JSONArray().apply { entries.forEach { put(it) } }.toString()

    @Test
    fun `an oversized payload is capped to the most recent, dropping the stalest`() {
        // A payload written under the historical cap of 5, which an existing
        // user still holds after the cap drops to MAX. Recents are stored
        // most-recent-first, so the cap must keep the *front* of the list.
        //
        // 5 is written literally rather than derived from MAX: it is the
        // historical cap this payload was written under, and tying it to today's
        // MAX would make the test tautological.
        val raw = payload(
            entry(id = "newest"),
            entry(id = "second", lng = 2.0),
            entry(id = "third", lng = 3.0),
            entry(id = "fourth", lng = 4.0),
            entry(id = "stalest", lng = 5.0),
        )
        val decoded = PrefsRecentSearchesStore.decode(raw)
        // Exact ids and order, not just size: the point of the assertion is
        // *which end* survives, which a size check would not catch.
        assertEquals(listOf("newest", "second", "third"), decoded.map { it.id })
    }

    @Test
    fun `a payload within the cap is preserved in order`() {
        val raw = payload(entry(id = "a"), entry(id = "b", lng = 2.0))
        assertEquals(listOf("a", "b"), PrefsRecentSearchesStore.decode(raw).map { it.id })
    }

    @Test
    fun `entries with unusable coordinates are skipped rather than surfaced at zero-zero`() {
        val raw = payload(
            entry(id = "ok"),
            JSONObject().put("id", "no-coords").put("name", "No coords"),
            entry(id = "out-of-range", lng = 999.0),
        )
        assertEquals(listOf("ok"), PrefsRecentSearchesStore.decode(raw).map { it.id })
    }

    @Test
    fun `a blank name is skipped`() {
        val raw = payload(entry(id = "ok"), entry(id = "nameless", name = "", lng = 2.0))
        assertEquals(listOf("ok"), PrefsRecentSearchesStore.decode(raw).map { it.id })
    }

    @Test
    fun `an absent payload decodes to an empty list`() {
        assertEquals(emptyList<PlaceSuggestion>(), PrefsRecentSearchesStore.decode(null))
        assertEquals(emptyList<PlaceSuggestion>(), PrefsRecentSearchesStore.decode("  "))
    }
}
