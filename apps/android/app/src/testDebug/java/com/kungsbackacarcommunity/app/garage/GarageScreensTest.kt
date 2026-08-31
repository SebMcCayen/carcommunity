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
        Vehicle(
            id = "v1",
            make = "Volvo",
            model = "240",
            makeId = "volvo",
            modelId = "240",
            modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL,
            engineDescription = "B230",
        )

    /** The accessibility label [CatalogueSelectorField] exposes for an EMPTY selector. */
    private fun emptySelector(labelRes: Int, placeholderRes: Int) =
        "${str(labelRes)}, ${str(placeholderRes)}"

    @Test
    fun emptyGarage_showsEmptyMessageAndInvokesOnAdd() {
        var added = 0
        composeTestRule.setContent {
            KccTheme {
                GarageScreen(
                    state = GarageState.Loaded(emptyList()),
                    vehicleLimit = 2,
                    onAdd = { added++ },
                    onEdit = {},
                    onDelete = {},
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
                    vehicleLimit = 2,
                    onAdd = {},
                    onEdit = { edited = it },
                    onDelete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.garage_editVehicle)).performScrollTo().performClick()
        assertEquals("v1", edited?.id)
    }

    @Test
    fun fullGarage_showsTierLimitAndHidesAdd() {
        composeTestRule.setContent {
            KccTheme {
                GarageScreen(
                    state =
                        GarageState.Loaded(
                            listOf(vehicle(), vehicle().copy(id = "v2", model = "740")),
                        ),
                    vehicleLimit = 2,
                    onAdd = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
        composeTestRule
            .onNodeWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.garage_vehicleLimitReached,
                    2,
                ),
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.garage_addVehicle)).assertDoesNotExist()
    }

    @Test
    fun form_validInput_reportsPayload() {
        var saved: VehicleInput? = null
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    // Make/model/year are SELECTED, never typed: they are seeded
                    // here as the pickers would set them. Driving the picker
                    // sheets themselves is not exercised on the emulator — the
                    // selection LOGIC (cascade, catalogue membership, year window,
                    // the Other bucket) is covered off-Compose by
                    // VehicleCatalogueTest / VehicleValidationTest, which run in
                    // the blocking gate rather than this informational job.
                    initial =
                        VehicleForm(
                            makeId = "saab",
                            modelId = "900",
                            modelYear = 1990,
                        ),
                    isEdit = false,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = { saved = it },
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_powertrain_petrol)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_saveVehicle)).performScrollTo().performClick()
        // The payload carries catalogue IDS; the backend derives the display text.
        assertEquals("saab", saved?.makeId)
        assertEquals("900", saved?.modelId)
        assertEquals(1990, saved?.modelYear)
        assertEquals(VehiclePowertrain.PETROL, saved?.powertrain)
    }

    @Test
    fun form_addMode_offersExactlyTheFourPowertrains() {
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
        // The four Seb asked for are offered...
        listOf(
            R.string.garage_powertrain_petrol,
            R.string.garage_powertrain_diesel,
            R.string.garage_powertrain_hybrid,
            R.string.garage_powertrain_electric,
        ).forEach { composeTestRule.onNodeWithText(str(it)).performScrollTo().assertIsDisplayed() }
        // ...and the retired two are not offered on a NEW vehicle.
        composeTestRule.onNodeWithText(str(R.string.garage_powertrain_plug_in_hybrid)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.garage_powertrain_other)).assertDoesNotExist()
    }

    @Test
    fun form_editingARetiredPowertrain_stillShowsItSelected() {
        // Backward compat at the UI layer: a pre-existing plug-in hybrid must
        // not render with nothing selected.
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial = VehicleForm(powertrain = VehiclePowertrain.PLUG_IN_HYBRID),
                    isEdit = true,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = {},
                    onCancel = {},
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.garage_powertrain_plug_in_hybrid))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun form_addMode_offersThePhotoSection() {
        // The add form could not attach a photo at all before: onChangePhoto was
        // hard-null in add mode, which hid the whole section.
        var picked = 0
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial = VehicleForm(),
                    isEdit = false,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = {},
                    onCancel = {},
                    onChangePhoto = { picked++ },
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.garage_photoAdd)).performScrollTo().performClick()
        assertEquals(1, picked)
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

    @Test
    fun form_modelSelector_isDisabledUntilAManufacturerIsChosen() {
        // The cascade, visible: model ids repeat across brands, so there is nothing
        // to offer (and no honest list to show) before a manufacturer is picked.
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
        composeTestRule
            .onNodeWithContentDescription(
                emptySelector(R.string.garage_model, R.string.garage_selectMakeFirst),
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun form_legacyVehicle_showsItsSavedTextAndAsksForASelection() {
        // Editing a car created before the catalogue: nothing is pre-selected (we
        // never guess which entry the old free text meant) and the saved text is
        // shown so the owner sees what they are replacing.
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial = VehicleForm(legacyMake = "Wolwo", legacyModel = "245", modelYear = 1985),
                    isEdit = true,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = {},
                    onCancel = {},
                )
            }
        }
        // Asserted through the selector's accessibility description, not the raw
        // text: CatalogueSelectorField clears its text field's semantics subtree
        // (which contains the supporting text), so the description is BOTH what a
        // screen reader announces and the only handle a test has on that text.
        composeTestRule
            .onNodeWithContentDescription(
                str(R.string.garage_legacySavedValue).format("Wolwo"),
                substring = true,
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(str(R.string.garage_legacyReselectHint))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun form_otherSelection_rendersItsLabelAndReportsTheOtherIds() {
        // The escape hatch: an unlisted brand is a SELECTION, so a member with a
        // rare import or a kit car is not locked out — and the form explains that
        // the choice is recorded rather than being a dead end.
        var saved: VehicleInput? = null
        composeTestRule.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial =
                        VehicleForm(
                            makeId = VehicleCatalogue.OTHER_ID,
                            modelId = VehicleCatalogue.OTHER_ID,
                            modelYear = 1998,
                        ),
                    isEdit = false,
                    saveStatus = VehicleSaveStatus.Idle,
                    currentYear = 2026,
                    onSave = { saved = it },
                    onCancel = {},
                )
            }
        }
        composeTestRule
            .onNodeWithText(str(R.string.garage_catalogueOtherHint))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.garage_powertrain_petrol)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.garage_saveVehicle)).performScrollTo().performClick()
        assertEquals(VehicleCatalogue.OTHER_ID, saved?.makeId)
        assertEquals(VehicleCatalogue.OTHER_ID, saved?.modelId)
    }
}
