package com.kungsbackacarcommunity.app.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import com.kungsbackacarcommunity.app.memberprofile.MemberBadges
import com.kungsbackacarcommunity.app.memberprofile.MemberProfile
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileScreen
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Config-less native tests: no real image URL, repositories or cloud mutations. */
@RunWith(AndroidJUnit4::class)
class ProfileVehicleHeroTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownCoverIsOptionalAndHiddenByTheExistingEditMode() {
        val cover = mutableStateOf<String?>(null)
        compose.setContent {
            KccTheme {
                ProfileScreen(
                    profile = UserProfile("Local owner", null, onboardingComplete = true),
                    saveStatus = ProfileEditStatus.Idle, onSave = { _, _, _ -> }, onBack = {}, onSignOut = {},
                    coverImagePath = cover.value,
                )
            }
        }
        compose.onNodeWithTag(PROFILE_VEHICLE_HERO_TAG).assertDoesNotExist()
        compose.runOnIdle { cover.value = "vehicleImages/local/main/cover.jpg" }
        compose.onNodeWithTag(PROFILE_VEHICLE_HERO_TAG).assertIsDisplayed()
        val edit = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.profile_editButton)
        compose.onNodeWithText(edit).performScrollTo().performClick()
        compose.onNodeWithTag(PROFILE_VEHICLE_HERO_TAG).assertDoesNotExist()
    }

    @Test fun memberCoverUsesLoadedMainCarAndIsWithdrawnWithTheProfile() {
        val car = Vehicle("local", "Volvo", "240", modelYear = 1988,
            powertrain = VehiclePowertrain.PETROL, engineDescription = null,
            imagePath = "vehicleImages/local/main/cover.jpg", isMainCar = true)
        val loaded = MemberProfileState.Loaded(MemberProfile("local", "Local member", null),
            listOf(car), MemberBadges.Available(emptyList()))
        val state = mutableStateOf<MemberProfileState>(loaded.copy(vehicles = listOf(car.copy(isMainCar = false))))
        compose.setContent { KccTheme { MemberProfileScreen(state.value, onRetry = {}) } }
        compose.onNodeWithTag(PROFILE_VEHICLE_HERO_TAG).assertDoesNotExist()
        compose.runOnIdle { state.value = loaded }
        compose.onNodeWithTag(PROFILE_VEHICLE_HERO_TAG).assertIsDisplayed()
        compose.runOnIdle { state.value = MemberProfileState.Blocked }
        compose.onNodeWithTag(PROFILE_VEHICLE_HERO_TAG).assertDoesNotExist()
    }
}
