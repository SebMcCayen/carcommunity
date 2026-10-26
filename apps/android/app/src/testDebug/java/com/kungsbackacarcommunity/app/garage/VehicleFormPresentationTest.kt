package com.kungsbackacarcommunity.app.garage

import androidx.compose.ui.test.assertIsDisplayed
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

@RunWith(AndroidJUnit4::class)
class VehicleFormPresentationTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun photoSectionAfterTheFieldsKeepsPickerAndSavedData() {
        var picks = 0
        var saved: VehicleInput? = null
        compose.setContent {
            KccTheme {
                VehicleFormScreen(
                    initial = VehicleForm(
                        makeId = "volvo", modelId = "240", modelYear = 1988,
                        powertrain = VehiclePowertrain.PETROL,
                        registrationPlate = "abc 123", modifications = "Owner's stored modifications",
                    ),
                    isEdit = true, saveStatus = VehicleSaveStatus.Idle, currentYear = 2026,
                    onSave = { saved = it }, onCancel = {}, onChangePhoto = { picks++ },
                )
            }
        }
        compose.onNodeWithText(str(R.string.garage_photoAdd)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, picks)
        compose.onNodeWithText(str(R.string.garage_saveVehicle)).performScrollTo().performClick()
        assertEquals("volvo", saved?.makeId)
        assertEquals("240", saved?.modelId)
        assertEquals("ABC 123", saved?.registrationPlate)
        assertEquals("Owner's stored modifications", saved?.modifications)
    }
}
