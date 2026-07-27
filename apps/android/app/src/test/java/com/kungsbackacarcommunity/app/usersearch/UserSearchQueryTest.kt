package com.kungsbackacarcommunity.app.usersearch

import java.util.Locale
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

    /**
     * Proves the fold is locale-INVARIANT by actually running it under a Turkish
     * default locale — the one that breaks naive lowercasing, mapping 'I' to
     * dotless 'ı'.
     *
     * Kotlin's no-argument `lowercase()` is invariant by contract (unlike the
     * deprecated `toLowerCase()`, and unlike `lowercase(Locale.getDefault())`),
     * but "the docs say so" is not something a reader can check at a glance, and
     * a well-meaning change to `lowercase(Locale.getDefault())` would look
     * harmless in review while quietly making every Turkish-locale user unable to
     * find anyone whose nickname contains an I. This test fails loudly if that
     * ever happens.
     *
     * The stored key is derived server-side by `toSearchKey`, which uses JS
     * `String.prototype.toLowerCase()` — also invariant by spec — so the two
     * sides agree regardless of the device's locale.
     */
    @Test
    fun `normalize is locale-invariant even under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // The trap: "ISAK".lowercase(Locale.getDefault()) would be "ısak".
            assertEquals("isak", UserSearchQuery.normalize("ISAK"))
            assertEquals('i', UserSearchQuery.normalize("ISAK")[0])
            assertEquals("gt_86", UserSearchQuery.normalize("GT_86"))
            // Sanity: the locale really was in effect, so this test cannot pass
            // vacuously if setDefault silently failed.
            assertEquals("ısak", "ISAK".lowercase(Locale.getDefault()))
        } finally {
            Locale.setDefault(original)
        }
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
