package com.kungsbackacarcommunity.app.location

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The location prompt's rendering contract.
 *
 * The prompt is stateless on purpose — the shell decides WHETHER location is
 * blocked, this decides only what to say about it — so every branch is
 * reachable here without simulating a real permission state on the device.
 */
@RunWith(AndroidJUnit4::class)
class LocationAccessPromptTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(
        access: LocationAccess,
        remedy: LocationPermissionRemedy = LocationPermissionRemedy.REQUEST_AGAIN,
        settingsUnavailable: Boolean = false,
        onFix: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KccTheme {
                LocationAccessPrompt(
                    access = access,
                    remedy = remedy,
                    settingsUnavailable = settingsUnavailable,
                    onFix = onFix,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun granted_showsNothing() {
        // The whole point of the "don't nag" rule: a working app says nothing.
        show(LocationAccess.GRANTED)
        composeTestRule.onNodeWithText(str(R.string.map_locationNeededTitle)).assertDoesNotExist()
    }

    @Test
    fun permissionDenied_explainsAndOffersTheSystemDialog() {
        show(LocationAccess.PERMISSION_DENIED, LocationPermissionRemedy.REQUEST_AGAIN)
        composeTestRule.onNodeWithText(str(R.string.map_locationNeededTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.map_locationPermissionBody)).assertIsDisplayed()
        // Still promptable → the short road, not a trip through Settings.
        composeTestRule.onNodeWithText(str(R.string.map_locationAllow)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.map_locationOpenSettings)).assertDoesNotExist()
    }

    @Test
    fun permanentlyDenied_offersSettingsInsteadOfADialogThatCannotOpen() {
        show(LocationAccess.PERMISSION_DENIED, LocationPermissionRemedy.OPEN_APP_SETTINGS)
        composeTestRule.onNodeWithText(str(R.string.map_locationOpenSettings)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.map_locationAllow)).assertDoesNotExist()
    }

    @Test
    fun servicesOff_saysSoAndAlwaysOffersSettings() {
        // A different sentence AND a different destination from a denied
        // permission — the app's permission page cannot turn the device
        // location switch back on.
        show(LocationAccess.SERVICES_OFF, LocationPermissionRemedy.REQUEST_AGAIN)
        composeTestRule.onNodeWithText(str(R.string.map_locationServicesOffBody)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.map_locationPermissionBody)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.map_locationOpenSettings)).assertIsDisplayed()
    }

    @Test
    fun settingsUnavailable_replacesTheActionRatherThanLeavingADeadButton() {
        // No settings activity resolved on this device. Showing the button
        // anyway would leave a control that visibly does nothing.
        show(LocationAccess.PERMISSION_DENIED, settingsUnavailable = true)
        composeTestRule.onNodeWithText(str(R.string.map_locationSettingsUnavailable))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.map_locationAllow)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.map_locationOpenSettings)).assertDoesNotExist()
        // Dismiss survives, so the card is never a trap.
        composeTestRule.onNodeWithText(str(R.string.map_locationDismiss)).assertIsDisplayed()
    }

    @Test
    fun actionsAreReported() {
        var fixed = 0
        var dismissed = 0
        show(
            LocationAccess.PERMISSION_DENIED,
            onFix = { fixed++ },
            onDismiss = { dismissed++ },
        )
        composeTestRule.onNodeWithText(str(R.string.map_locationAllow)).performClick()
        composeTestRule.onNodeWithText(str(R.string.map_locationDismiss)).performClick()
        assertEquals(1, fixed)
        assertEquals(1, dismissed)
    }
}
