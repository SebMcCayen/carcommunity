package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GaragePhotoDensityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactPhotosKeepTwoCarsAndAddReachableAt320By640() {
        val cars = listOf(
            Vehicle("a", "Volvo", "V60", modelYear = 2020, powertrain = VehiclePowertrain.HYBRID, engineDescription = null),
            Vehicle("b", "Saab", "900", modelYear = 1990, powertrain = VehiclePowertrain.PETROL, engineDescription = null),
        )
        compose.setContent {
            KccTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    GarageScreen(GarageState.Loaded(cars), 5, onAdd = {}, onEdit = {}, onDelete = {})
                }
            }
        }
        val photo = compose.onNodeWithTag(garagePhotoTag("a"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(2.5f, (photo.right.value - photo.left.value) / (photo.bottom.value - photo.top.value), 0.03f)
        compose.onNodeWithText("Volvo V60 (2020)").assertIsDisplayed()
        compose.onNodeWithText("Saab 900 (1990)").performScrollTo().assertIsDisplayed()
        val add = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.garage_addVehicle)
        compose.onNodeWithText(add).performScrollTo().assertIsDisplayed()
    }
}
