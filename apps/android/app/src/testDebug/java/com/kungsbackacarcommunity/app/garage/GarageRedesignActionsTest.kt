package com.kungsbackacarcommunity.app.garage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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

    private fun menuLabel(car: Vehicle = vehicle) = str(R.string.savedPlaces_moreActions).format(
        VehicleDisplay.headline(car, str(R.string.garage_catalogueOther)),
    )

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
        compose.onNodeWithContentDescription(menuLabel()).performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(str(R.string.garage_unsetMainCar)).assertIsDisplayed().performClick()
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
        compose.onNodeWithContentDescription(menuLabel()).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.garage_deleteVehicle)).assertIsDisplayed().performClick()
        assertEquals(null, deleted)
        compose.onNodeWithText(str(R.string.garage_deleteConfirmButton)).performClick()
        assertEquals("local-car", deleted)
        assertEquals(0, opened)
    }

    @Test fun overflowNamesTheCarAndAccessibilityClickOpensOnlyItsMenu() {
        var opened = 0
        var edited: String? = null
        compose.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle)), vehicleLimit = 2,
                    onAdd = {}, onEdit = { edited = it.id }, onDelete = {}, onOpen = { opened++ },
                )
            }
        }
        compose.onNodeWithText(str(R.string.garage_editVehicle)).assertDoesNotExist()
        compose.onNodeWithContentDescription(menuLabel()).performScrollTo()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, opened)
        compose.onNodeWithText(str(R.string.garage_unsetMainCar)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.garage_deleteVehicle)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.garage_editVehicle)).assertIsDisplayed().performClick()
        assertEquals(vehicle.id, edited)
        assertEquals(0, opened)
        compose.onNodeWithText(str(R.string.garage_editVehicle)).assertDoesNotExist()
    }

    @Test fun overflowSetsTheChosenNonMainCarWithoutOpeningDetail() {
        val second = vehicle.copy(id = "second-car", model = "9000", isMainCar = false)
        var main: Pair<String, Boolean>? = null
        var opened = 0
        compose.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle, second)), vehicleLimit = 2,
                    onAdd = {}, onEdit = {}, onDelete = {}, onOpen = { opened++ },
                    onSetMain = { id, value -> main = id to value },
                )
            }
        }
        compose.onNodeWithContentDescription(menuLabel(second)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.garage_unsetMainCar)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.garage_setMainCar)).assertIsDisplayed().performClick()
        assertEquals(second.id to true, main)
        assertEquals(0, opened)
    }

    @Test fun cancelledDeletionKeepsTheCarAndAllowsReopeningItsMenu() {
        var deleted = 0
        var opened = 0
        compose.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(listOf(vehicle)), vehicleLimit = 2,
                    onAdd = {}, onEdit = {}, onDelete = { deleted++ }, onOpen = { opened++ },
                )
            }
        }
        compose.onNodeWithContentDescription(menuLabel()).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.garage_deleteVehicle)).performClick()
        compose.onNodeWithText(str(R.string.garage_deleteConfirm)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.garage_cancelButton)).performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Saab 900 (1990)").assertIsDisplayed()
        compose.onNodeWithContentDescription(menuLabel()).performClick()
        compose.onNodeWithText(str(R.string.garage_editVehicle)).assertIsDisplayed()
        assertEquals(0, opened)
    }
}
