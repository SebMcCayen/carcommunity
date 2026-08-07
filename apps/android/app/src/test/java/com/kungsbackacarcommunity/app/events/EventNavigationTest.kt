package com.kungsbackacarcommunity.app.events

import com.kungsbackacarcommunity.app.navigation.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the event Navigate routing decision ([EventNavigation.navigateAction]).
 *
 * The behavior under test is the whole point of the in-app-navigation fix: the
 * event detail's Navigate button must hand off to the app's OWN in-app
 * navigate-to-point flow (with the event's coordinates + label), NOT the device's
 * external maps app, whenever that in-app handoff is wired.
 */
class EventNavigationTest {
    private val point = LatLng(longitude = 12.0757, latitude = 57.4874)

    @Test
    fun `prefers the in-app handoff and forwards the event coordinates and label`() {
        var inAppArgs: Triple<Double, Double, String?>? = null
        var externalCalled = false

        val action =
            EventNavigation.navigateAction(
                point = point,
                label = "Cars & Coffee",
                onNavigateToPoint = { lat, lng, name -> inAppArgs = Triple(lat, lng, name) },
                onExternalFallback = { externalCalled = true },
            )

        assertNotNull(action)
        action!!.invoke()
        assertEquals(Triple(57.4874, 12.0757, "Cars & Coffee"), inAppArgs)
        assertFalse("the external maps launcher must not be used when in-app nav is wired", externalCalled)
    }

    @Test
    fun `in-app handoff wins even when an external fallback is also present`() {
        var inAppCalled = false
        var externalCalled = false

        EventNavigation.navigateAction(
            point = point,
            label = "Torg",
            onNavigateToPoint = { _, _, _ -> inAppCalled = true },
            onExternalFallback = { externalCalled = true },
        )!!.invoke()

        assertTrue(inAppCalled)
        assertFalse(externalCalled)
    }

    @Test
    fun `falls back to the external launcher only when no in-app handoff is wired`() {
        var externalCalled = false

        val action =
            EventNavigation.navigateAction(
                point = point,
                label = "Torg",
                onNavigateToPoint = null,
                onExternalFallback = { externalCalled = true },
            )

        assertNotNull(action)
        action!!.invoke()
        assertTrue(externalCalled)
    }

    @Test
    fun `no pin yields no action so the Navigate button hides`() {
        assertNull(
            EventNavigation.navigateAction(
                point = null,
                label = "Torg",
                onNavigateToPoint = { _, _, _ -> },
                onExternalFallback = {},
            ),
        )
    }

    @Test
    fun `no in-app handoff and no fallback yields no action`() {
        assertNull(
            EventNavigation.navigateAction(
                point = point,
                label = "Torg",
                onNavigateToPoint = null,
                onExternalFallback = null,
            ),
        )
    }
}
