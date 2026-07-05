package com.kungsbackacarcommunity.app.profile

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
class ProfileScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val profile = UserProfile(displayName = "Sebbe", bio = "Volvo fan", onboardingComplete = true)

    @Test
    fun viewModeShowsProfileAndEditEntersEditMode() {
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { _, _ -> },
                    onBack = {},
                    onSignOut = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Sebbe").assertIsDisplayed()
        composeTestRule.onNodeWithText("Volvo fan").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.profile_editButton)).performScrollTo().performClick()
        // Edit mode: the display-name field label appears.
        composeTestRule.onNodeWithText(str(R.string.profile_displayNameLabel)).assertIsDisplayed()
    }

    @Test
    fun savingSeededValidProfileInvokesOnSave() {
        var saved: Pair<String, String>? = null
        composeTestRule.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile,
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { name, bio -> saved = name to bio },
                    onBack = {},
                    onSignOut = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.profile_editButton)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.profile_saveButton)).performScrollTo().performClick()
        assertEquals("Sebbe" to "Volvo fan", saved)
    }
}
