package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
    fun preselectedMainCar_isRenderedAsTheSelectedItem() {
        // The main car ("b") is NOT the first car in garage order, so this guards
        // the end-to-end path: the default id from defaultStartDrivingVehicleId is
        // applied to the picker and lands the selected ring on the MAIN car, not
        // the first one.
        val vehicles = listOf(car("a", "240"), car("b", "740", isMainCar = true))
        val default = defaultStartDrivingVehicleId(vehicles)
        composeTestRule.setContent {
            KccTheme {
                StartDrivingCarPicker(
                    vehicles = vehicles,
                    selectedVehicleId = default,
                    onSelectVehicle = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Volvo 740").assertIsSelected()
        composeTestRule.onNodeWithContentDescription("Volvo 240").assertIsNotSelected()
    }

    @Test
    fun preselectedCarOffScreen_isScrolledIntoView() {
        // Six fixed-width round photos in a narrow (single-item) viewport: the
        // preselected MAIN car is last, so at scroll 0 it sits well off the right
        // edge. The picker must scroll it into view on open, otherwise the popup
        // would open showing only unselected cars — the reported "main car isn't
        // preselected" symptom. The first car must then be scrolled out of view.
        val vehicles = (0..5).map { i -> car("v$i", "model$i", isMainCar = i == 5) }
        val default = defaultStartDrivingVehicleId(vehicles)
        composeTestRule.setContent {
            KccTheme {
                Box(Modifier.width(120.dp)) {
                    StartDrivingCarPicker(
                        vehicles = vehicles,
                        selectedVehicleId = default,
                        onSelectVehicle = {},
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Volvo model5").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Volvo model0").assertIsNotDisplayed()
    }

    @Test
    fun selectingFirstCarAfterScrolling_bringsItBackIntoView() {
        // Start with the last car selected (row scrolls to the end, so the first
        // car is off-screen). Selecting the first car must scroll the row back so
        // it is visible again — index 0 is not treated as "nothing to do".
        val vehicles = (0..5).map { i -> car("v$i", "model$i") }
        var selected by mutableStateOf("v5")
        composeTestRule.setContent {
            KccTheme {
                Box(Modifier.width(120.dp)) {
                    StartDrivingCarPicker(
                        vehicles = vehicles,
                        selectedVehicleId = selected,
                        onSelectVehicle = { selected = it },
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Volvo model0").assertIsNotDisplayed()

        selected = "v0"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Volvo model0").assertIsDisplayed()
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
