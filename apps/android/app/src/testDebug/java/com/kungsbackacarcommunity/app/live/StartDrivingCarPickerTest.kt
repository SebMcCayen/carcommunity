package com.kungsbackacarcommunity.app.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun showsTheVehicleNameAndYear_andTapReportsItsId() {
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

        // One selectable item per car, labelled by make, model and year.
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo 740 (1988)").assertIsDisplayed()

        // Tapping the non-selected car reports its id up to the caller.
        composeTestRule.onNodeWithText("Volvo 240 (1988)").performClick()
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

        composeTestRule.onNodeWithText("Volvo 740 (1988)").assertIsSelected()
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsNotSelected()
    }

    @Test
    fun ranksThreeVehicles_andShowsTheSelectedVehicleOutsideTheQuickList() {
        val vehicles = (0..4).map { i -> car("v$i", "model$i", isMainCar = i == 4) }
        var selected by mutableStateOf("v4")
        var showAll by mutableStateOf(false)
        composeTestRule.setContent {
            KccTheme {
                StartDrivingCarPicker(
                    vehicles = vehicles,
                    selectedVehicleId = selected,
                    onSelectVehicle = { selected = it; showAll = false },
                    rankedVehicleIds = listOf("v2", "v0", "v3"),
                    showAll = showAll,
                    onShowAll = { showAll = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Volvo model2 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo model0 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo model3 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo model1 (1988)").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.shell_createChooserAllVehicles)).performClick()
        composeTestRule.onNodeWithText("Volvo model4 (1988)").assertIsSelected()
        composeTestRule.onNodeWithText("Volvo model1 (1988)").performClick()
        assertEquals("v1", selected)
        val summary = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.shell_createChooserSelectedVehicle, "Volvo model1 (1988)")
        composeTestRule.onNodeWithText(summary).assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo model1 (1988)").assertDoesNotExist()
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
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertDoesNotExist()
    }
}
