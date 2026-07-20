package com.kungsbackacarcommunity.app.garage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Compose UI tests for the car-detail page + tap-to-open (car-detail-gallery). */
@RunWith(AndroidJUnit4::class)
class VehicleDetailScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun vehicle() =
        Vehicle(
            id = "v1",
            make = "Volvo",
            model = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
            modifications = "Lowered on Bilstein",
        )

    @Test
    fun tappingACard_opensTheDetailPage() {
        var opened: Vehicle? = null
        composeTestRule.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle())),
                    onAdd = {},
                    onEdit = {},
                    onDelete = {},
                    onOpen = { opened = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Volvo 240 (1988)").performClick()
        assertEquals("v1", opened?.id)
    }

    @Test
    fun detailPage_showsAllInfoAndDisabledAddMore() {
        composeTestRule.setContent {
            KccTheme {
                VehicleDetailScreen(
                    vehicle = vehicle(),
                    onEdit = {},
                    onDelete = {},
                    onSetMain = {},
                    // onAddPhoto left null: single-photo model today.
                )
            }
        }
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText("B230").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Lowered on Bilstein").performScrollTo().assertIsDisplayed()
        // The "add more photos" affordance is present but disabled, with its
        // explanation, until the backend can store more than one photo.
        composeTestRule.onNodeWithText(str(R.string.garage_photoAddMore))
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText(str(R.string.garage_photoAddMoreUnavailable))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun detailPage_editAndDelete_invokeCallbacks() {
        var edited = 0
        var deleted = 0
        composeTestRule.setContent {
            KccTheme {
                VehicleDetailScreen(
                    vehicle = vehicle(),
                    onEdit = { edited++ },
                    onDelete = { deleted++ },
                    onSetMain = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_editVehicle)).performScrollTo().performClick()
        assertEquals(1, edited)
        // Delete asks for confirmation first.
        composeTestRule.onNodeWithText(str(R.string.garage_deleteVehicle)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_deleteConfirmButton)).performClick()
        assertEquals(1, deleted)
    }
}
