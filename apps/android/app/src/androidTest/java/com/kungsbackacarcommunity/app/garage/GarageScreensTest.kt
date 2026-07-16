package com.kungsbackacarcommunity.app.garage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the garage screens (Phase 12 slice 13).
 */
@RunWith(AndroidJUnit4::class)
class GarageScreensTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun vehicle() =
        Vehicle("v1", "Volvo", "240", 1988, VehiclePowertrain.PETROL, "B230")

    @Test
    fun emptyGarage_showsEmptyMessageAndInvokesOnAdd() {
        var added = 0
        composeTestRule.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(emptyList()),
                    onAdd = { added++ },
                    onEdit = {},
                    onDelete = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.garage_addVehicle)).performScrollTo().performClick()
        assertEquals(1, added)
    }

    @Test
    fun vehicleCard_showsVehicleAndInvokesOnEdit() {
        var edited: Vehicle? = null
        composeTestRule.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle())),
                    onAdd = {},
                    onEdit = { edited = it },
                    onDelete = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.garage_editVehicle)).performScrollTo().performClick()
        assertEquals("v1", edited?.id)
    }

    @Test
    fun form_validInput_reportsPayload() {
        var saved: VehicleInput? = null
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial = VehicleForm(),
                    isEdit = false,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = { saved = it },
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_make)).performTextInput("Saab")
        composeTestRule.onNodeWithText(str(R.string.garage_model)).performTextInput("900")
        composeTestRule.onNodeWithText(str(R.string.garage_modelYear)).performTextInput("1990")
        composeTestRule.onNodeWithText(str(R.string.garage_powertrain_petrol)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_saveVehicle)).performScrollTo().performClick()
        assertEquals("Saab", saved?.make)
        assertEquals(1990, saved?.modelYear)
        assertEquals(VehiclePowertrain.PETROL, saved?.powertrain)
    }

    @Test
    fun form_missingFields_showsValidationError() {
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial = VehicleForm(),
                    isEdit = false,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = {},
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_saveVehicle)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_validationMakeRequired)).assertIsDisplayed()
    }
}
