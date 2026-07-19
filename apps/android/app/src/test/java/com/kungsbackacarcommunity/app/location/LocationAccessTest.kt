package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location-access decision matrix.
 *
 * The runtime states themselves (a real denial, a real "don't ask again", a
 * real device toggle) are awkward to drive from a test — which is exactly why
 * the decisions are pure functions rather than inline `when`s in the shell.
 * What is asserted here is the classification and the remedy; what is NOT
 * asserted anywhere is the platform actually reporting those states, which is
 * left to the device.
 */
class LocationAccessTest {

    @Test
    fun `permission denied is reported ahead of the device toggle`() {
        // Both broken: the app's own missing permission is the actionable one.
        // Sending this user to the device location toggle would show a switch
        // that is already on, with nothing for them to do.
        assertEquals(
            LocationAccess.PERMISSION_DENIED,
            locationAccessOf(permissionGranted = false, locationServicesEnabled = false),
        )
        assertEquals(
            LocationAccess.PERMISSION_DENIED,
            locationAccessOf(permissionGranted = false, locationServicesEnabled = true),
        )
    }

    @Test
    fun `permission granted but services off is its own state`() {
        // NOT collapsed into PERMISSION_DENIED: this user's permission is
        // already granted, so the app's permission page cannot fix anything.
        assertEquals(
            LocationAccess.SERVICES_OFF,
            locationAccessOf(permissionGranted = true, locationServicesEnabled = false),
        )
    }

    @Test
    fun `both available is granted and not blocked`() {
        val access = locationAccessOf(permissionGranted = true, locationServicesEnabled = true)
        assertEquals(LocationAccess.GRANTED, access)
        assertFalse(access.isBlocked)
    }

    @Test
    fun `every non-granted state is blocked`() {
        assertTrue(LocationAccess.PERMISSION_DENIED.isBlocked)
        assertTrue(LocationAccess.SERVICES_OFF.isBlocked)
    }

    @Test
    fun `a permanent denial routes to settings, not to a dialog that cannot open`() {
        // The one case that MUST NOT offer a re-prompt: launching the request
        // after "don't ask again" returns denied instantly without showing
        // anything, which to the user is a button that does nothing.
        assertEquals(
            LocationPermissionRemedy.OPEN_APP_SETTINGS,
            locationPermissionRemedy(canShowRationale = false, alreadyAsked = true),
        )
    }

    @Test
    fun `an ordinary denial re-prompts rather than sending the user to settings`() {
        assertEquals(
            LocationPermissionRemedy.REQUEST_AGAIN,
            locationPermissionRemedy(canShowRationale = true, alreadyAsked = true),
        )
    }

    @Test
    fun `never asked re-prompts even though the rationale flag is also false`() {
        // shouldShowRequestPermissionRationale is false BOTH before the first
        // ask and after a permanent denial; alreadyAsked is what separates
        // them. Getting this backwards would send first-time users to Settings
        // to fix a permission they were never asked for.
        assertEquals(
            LocationPermissionRemedy.REQUEST_AGAIN,
            locationPermissionRemedy(canShowRationale = false, alreadyAsked = false),
        )
        assertEquals(
            LocationPermissionRemedy.REQUEST_AGAIN,
            locationPermissionRemedy(canShowRationale = true, alreadyAsked = false),
        )
    }
}
