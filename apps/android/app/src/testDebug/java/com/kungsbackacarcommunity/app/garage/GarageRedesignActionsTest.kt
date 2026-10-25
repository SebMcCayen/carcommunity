package com.kungsbackacarcommunity.app.garage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarageRedesignActionsTest {
    @get:Rule val compose = createComposeRule()

    private val vehicle = Vehicle(
        id = "local-car", make = "Saab", model = "900", modelYear = 1990,
        powertrain = VehiclePowertrain.PETROL, engineDescription = null, isMainCar = true,
    )

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun missingPhotoUsesNativeFallbackAndOpenRemainsSeparateFromMainAction() {
        var opened = 0
        var main: Pair<String, Boolean>? = null
        compose.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle)), vehicleLimit = 2,
                    onAdd = {}, onEdit = {}, onDelete = {},
                    onOpen = { opened++ }, onSetMain = { id, value -> main = id to value },
                )
            }
        }
        compose.onNodeWithContentDescription(str(R.string.garage_photoNone)).assertIsDisplayed()
        compose.onNodeWithText("Saab 900 (1990)").performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(str(R.string.garage_unsetMainCar)).performScrollTo().performClick()
        assertEquals("local-car" to false, main)
        assertEquals(1, opened)
    }

    @Test fun destructiveActionStillWaitsForConfirmationAndKeepsDetailClosed() {
        var deleted: String? = null
        var opened = 0
        compose.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle)), vehicleLimit = 2,
                    onAdd = {}, onEdit = {}, onDelete = { deleted = it }, onOpen = { opened++ },
                )
            }
        }
        compose.onNodeWithText(str(R.string.garage_deleteVehicle)).performScrollTo().performClick()
        assertEquals(null, deleted)
        compose.onNodeWithText(str(R.string.garage_deleteConfirmButton)).performClick()
        assertEquals("local-car", deleted)
        assertEquals(0, opened)
    }
}
