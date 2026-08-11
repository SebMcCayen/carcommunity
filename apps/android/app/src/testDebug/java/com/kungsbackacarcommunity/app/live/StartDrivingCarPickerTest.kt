package com.kungsbackacarcommunity.app.live

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric-backed Compose UI test (fast, blocking `testDebugUnitTest`, no
 * emulator) for the "Start driving" car picker. Lives in `src/testDebug` for the
 * same reason as the other Compose unit tests: the ComponentActivity host
 * `createComposeRule()` launches into comes from the debug-only
 * `ui-test-manifest` dependency.
 */
@RunWith(AndroidJUnit4::class)
class StartDrivingCarPickerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun car(id: String, model: String, isMainCar: Boolean = false) =
        Vehicle(
            id = id,
            make = "Volvo",
            model = model,
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = null,
            isMainCar = isMainCar,
        )

    @Test
    fun rendersOneRoundPhotoPerCar_andTapReportsItsId() {
        val vehicles = listOf(car("a", "240"), car("b", "740", isMainCar = true))
        var picked: String? = null
        composeTestRule.setContent {
            KccTheme {
                StartDrivingCarPicker(
                    vehicles = vehicles,
                    // Main car preselected — mirrors the parent's default.
                    selectedVehicleId = "b",
                    onSelectVehicle = { picked = it },
                )
            }
        }

        // One selectable item per car, labelled by make+model.
        composeTestRule.onNodeWithContentDescription("Volvo 240").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Volvo 740").assertIsDisplayed()

        // Tapping the non-selected car reports its id up to the caller.
        composeTestRule.onNodeWithContentDescription("Volvo 240").performClick()
        assertEquals("a", picked)
    }

    @Test
    fun emptyGarage_showsTheNoCarsHint_andNoItems() {
        composeTestRule.setContent {
            KccTheme {
                StartDrivingCarPicker(
                    vehicles = emptyList(),
                    selectedVehicleId = null,
                    onSelectVehicle = {},
                )
            }
        }

        // The "no cars" hint is shown; no car items exist.
        composeTestRule.onNodeWithText(str(R.string.shell_createChooserNoCars)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Volvo 240").assertDoesNotExist()
    }
}
