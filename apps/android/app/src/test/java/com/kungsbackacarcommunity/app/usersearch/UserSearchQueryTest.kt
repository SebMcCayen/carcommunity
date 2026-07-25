package com.kungsbackacarcommunity.app.usersearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the client-side query normalization and min-length gate. These must agree
 * with the BACKEND's rule (functions/src/users/user-search-core.ts toSearchKey /
 * isSearchableKey) — a disagreement is invisible at compile time and shows up
 * only as a search that quietly returns nothing.
 */
class UserSearchQueryTest {

    @Test
    fun `normalize trims and folds case`() {
        assertEquals("gt_86", UserSearchQuery.normalize("GT_86"))
        assertEquals("gt_86", UserSearchQuery.normalize("  Gt_86  "))
        assertEquals("gt", UserSearchQuery.normalize("gt"))
    }

    @Test
    fun `normalize folds locale-invariantly`() {
        // The Turkish trap: a locale-SENSITIVE fold maps 'I' to dotless 'ı',
        // which would stop matching the key the backend stored.
        assertEquals("isak", UserSearchQuery.normalize("ISAK"))
        assertEquals('i', UserSearchQuery.normalize("ISAK")[0])
        assertEquals("åke", UserSearchQuery.normalize("ÅKE"))
    }

    @Test
    fun `an empty or whitespace-only query normalizes to empty`() {
        assertEquals("", UserSearchQuery.normalize(""))
        assertEquals("", UserSearchQuery.normalize("   "))
    }

    @Test
    fun `a query shorter than the minimum is not searchable`() {
        assertEquals(2, UserSearchQuery.MIN_QUERY_CODE_POINTS)
        assertFalse(UserSearchQuery.isSearchable(""))
        assertFalse(UserSearchQuery.isSearchable("g"))
    }

    @Test
    fun `the minimum and anything longer is searchable`() {
        assertTrue(UserSearchQuery.isSearchable("gt"))
        assertTrue(UserSearchQuery.isSearchable("gt_86"))
    }

    @Test
    fun `whitespace never counts towards the minimum length`() {
        assertFalse(UserSearchQuery.isSearchable(UserSearchQuery.normalize("  g  ")))
        assertTrue(UserSearchQuery.isSearchable(UserSearchQuery.normalize("  gt  ")))
    }

    @Test
    fun `length is counted in code points, not UTF-16 units`() {
        val oneEmoji = "😀"
        // Two UTF-16 units but ONE character to the person typing it: a
        // `length >= 2` gate would send a query the backend then rejects.
        assertEquals(2, oneEmoji.length)
        assertFalse(UserSearchQuery.isSearchable(oneEmoji))
        assertTrue(UserSearchQuery.isSearchable(oneEmoji + "🚗"))
    }
}
