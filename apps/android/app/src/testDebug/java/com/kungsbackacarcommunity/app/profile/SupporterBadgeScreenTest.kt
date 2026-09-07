package com.kungsbackacarcommunity.app.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.memberprofile.MemberBadges
import com.kungsbackacarcommunity.app.memberprofile.MemberProfile
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileScreen
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupporterBadgeScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun ownPlaceholderCrownRespondsToPreferenceAndEligibility() {
        val badge = mutableStateOf(SupporterBadge(eligible = true))
        compose.setContent {
            KccTheme {
                ProfileScreen(
                    profile = UserProfile("Owner", null, onboardingComplete = true, supporterBadge = badge.value),
                    saveStatus = ProfileEditStatus.Idle, onSave = { _, _, _ -> }, onBack = {}, onSignOut = {},
                )
            }
        }
        compose.onNodeWithContentDescription(str(R.string.supporterBadge_accessibility)).assertIsDisplayed()
        compose.runOnIdle { badge.value = SupporterBadge(true, false) }
        compose.onNodeWithContentDescription(str(R.string.supporterBadge_accessibility)).assertDoesNotExist()
        compose.runOnIdle { badge.value = SupporterBadge(false, true) }
        compose.onNodeWithContentDescription(str(R.string.supporterBadge_accessibility)).assertDoesNotExist()
    }

    @Test fun memberPlaceholderCrownUpdatesWithoutPrivateSubscriptionData() {
        val badge = mutableStateOf(SupporterBadge(true))
        compose.setContent {
            KccTheme(darkTheme = true) {
                MemberProfileScreen(
                    state = MemberProfileState.Loaded(
                        MemberProfile("member", "Member", null, supporterBadge = badge.value),
                        emptyList(), MemberBadges.Available(emptyList()),
                    ), onRetry = {},
                )
            }
        }
        compose.onNodeWithContentDescription(str(R.string.supporterBadge_accessibility)).assertIsDisplayed()
        compose.runOnIdle { badge.value = SupporterBadge() }
        compose.onNodeWithContentDescription(str(R.string.supporterBadge_accessibility)).assertDoesNotExist()
    }

    @Test fun settingsDefaultOnToggleFailureAndLoadingSemantics() {
        val badge = mutableStateOf<SupporterBadge?>(SupporterBadge())
        val status = mutableStateOf<ProfileEditStatus>(ProfileEditStatus.Idle)
        var saved: Boolean? = null
        compose.setContent {
            KccTheme {
                SupporterBadgeSettingRow(badge.value, status.value) { saved = it }
            }
        }
        val title = str(R.string.supporterBadge_settingTitle)
        compose.onNodeWithContentDescription(title).assertIsOn().performClick()
        compose.runOnIdle { assertEquals(false, saved); badge.value = SupporterBadge(show = false) }
        compose.onNodeWithContentDescription(title).assertIsOff()
        compose.runOnIdle { status.value = ProfileEditStatus.Saving }
        compose.onNodeWithContentDescription(title).assertIsNotEnabled()
        compose.runOnIdle { status.value = ProfileEditStatus.Failed }
        compose.onNodeWithText(str(R.string.supporterBadge_saveFailed)).assertIsDisplayed()
        compose.runOnIdle { badge.value = null }
        compose.onNodeWithContentDescription(title).assertIsNotEnabled()
    }
}
