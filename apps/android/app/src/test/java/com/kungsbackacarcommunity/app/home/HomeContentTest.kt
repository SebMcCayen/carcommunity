package com.kungsbackacarcommunity.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure home-shell presentation logic (Phase 12 slice 1).
 */
class HomeContentTest {

    @Test
    fun `greetingName returns a present display name`() {
        assertEquals("Sebbe", HomeContent.greetingName("Sebbe"))
    }

    @Test
    fun `greetingName trims surrounding whitespace`() {
        assertEquals("Sebbe", HomeContent.greetingName("  Sebbe  "))
    }

    @Test
    fun `greetingName collapses null to null`() {
        assertNull(HomeContent.greetingName(null))
    }

    @Test
    fun `greetingName collapses blank and whitespace to null`() {
        assertNull(HomeContent.greetingName(""))
        assertNull(HomeContent.greetingName("   "))
    }
}
