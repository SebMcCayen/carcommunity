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
import com.kungsbackacarcommunity.app.memberprofile.MemberBadges
import com.kungsbackacarcommunity.app.memberprofile.MemberProfile
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileScreen
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileClubBadgeTest {
    @get:Rule val compose = createComposeRule()
    private val badge = requireNotNull(ClubBadge.fromField(
        mapOf("clubId" to "club_12345678", "clubName" to "Östra Klubben", "logoUrl" to null),
    ))
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun ownerRendersBothBadgesAndEditingStillSavesOnlyEditableIdentity() {
        var saved: Triple<String, String, SocialHandles>? = null
        compose.setContent {
            KccTheme {
                ProfileScreen(
                    profile = UserProfile("Ada", "Car enthusiast", onboardingComplete = true,
                        supporterBadge = SupporterBadge(eligible = true), clubBadge = badge),
                    saveStatus = ProfileEditStatus.Idle,
                    onSave = { name, bio, social -> saved = Triple(name, bio, social) },
                    onBack = {}, onSignOut = {},
                )
            }
        }
        compose.onNodeWithTag("club_avatar_badge").assertIsDisplayed()
        compose.onNodeWithTag("supporter_avatar_crown").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.profile_editButton)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.profile_saveButton)).performScrollTo().performClick()
        assertEquals(Triple("Ada", "Car enthusiast", SocialHandles.EMPTY), saved)
    }

    @Test fun memberProfileReplacesClearedMembershipWithoutRemovingSupporter() {
        val profile = mutableStateOf(MemberProfile("member_12345", "Ada", null,
            supporterBadge = SupporterBadge(eligible = true), clubBadge = badge))
        compose.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = MemberProfileState.Loaded(profile.value, emptyList(), MemberBadges.Available(emptyList())),
                    onRetry = {}, onBlock = null,
                )
            }
        }
        compose.onNodeWithTag("club_avatar_badge").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { profile.value = profile.value.copy(clubBadge = null) }
        compose.onNodeWithTag("club_avatar_badge").assertDoesNotExist()
        compose.onNodeWithTag("supporter_avatar_crown").performScrollTo().assertIsDisplayed()
    }
}
