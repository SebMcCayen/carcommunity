package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalNavigationTest {
    @Test
    fun `navigation uri targets driving turn-by-turn with dot decimals`() {
        // lat,lng order, six decimals, locale-independent dot separator, mode=d.
        val uri = ExternalNavigation.navigationUri(LatLng(longitude = 12.0757, latitude = 57.4874))
        assertEquals("google.navigation:q=57.487400,12.075700&mode=d", uri)
    }
}
