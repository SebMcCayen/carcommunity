package com.kungsbackacarcommunity.app.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
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
import com.kungsbackacarcommunity.app.memberprofile.MemberAvatarBadges
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileRepository
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileResult
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileRoute
import kotlinx.coroutines.flow.MutableStateFlow
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
    private fun label(name: String = badge.clubName) =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.clubBadge_accessibility, name)

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
        compose.onNodeWithText(label()).assertIsDisplayed().assertHasNoClickAction()
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
        compose.onNodeWithText(label()).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { profile.value = profile.value.copy(clubBadge = null) }
        compose.onNodeWithTag("club_avatar_badge").assertDoesNotExist()
        compose.onNodeWithTag("profile_club_affiliation").assertDoesNotExist()
        compose.onNodeWithTag("supporter_avatar_crown").performScrollTo().assertIsDisplayed()
    }

    @Test fun ownerWithoutMembershipShowsNoAffiliationUntilPublicProjectionArrives() {
        val profile = mutableStateOf(UserProfile("Ada", null, onboardingComplete = true))
        compose.setContent {
            KccTheme {
                ProfileScreen(
                    profile = profile.value, saveStatus = ProfileEditStatus.Idle,
                    onSave = { _, _, _ -> }, onBack = {}, onSignOut = {},
                )
            }
        }
        compose.onNodeWithTag("profile_club_affiliation").assertDoesNotExist()
        compose.runOnIdle { profile.value = profile.value.copy(clubBadge = badge) }
        compose.onNodeWithText(label()).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { profile.value = profile.value.copy(clubBadge = null) }
        compose.onNodeWithTag("profile_club_affiliation").assertDoesNotExist()
    }

    @Test fun visibleMemberRouteUsesExistingPublicProjectionForClubChangesAndClearing() {
        val badges = MutableStateFlow(MemberAvatarBadges(SupporterBadge(), badge))
        var observations = 0
        val repository = object : MemberProfileRepository {
            override fun observeAvatarBadges(uid: String) = badges.also { observations++ }
            override suspend fun loadMemberProfile(targetUid: String) = MemberProfileResult.Loaded(
                MemberProfile(targetUid, "Ada", null, clubBadge = badge),
                emptyList(), MemberBadges.Available(emptyList()),
            )
        }
        compose.setContent {
            KccTheme {
                MemberProfileRoute(
                    repository = repository, targetUid = "member", viewerUid = "viewer",
                    blockingRepository = null,
                )
            }
        }
        compose.onNodeWithText(label()).performScrollTo().assertIsDisplayed()
        val renamed = requireNotNull(ClubBadge.fromField(
            mapOf("clubId" to "new_club", "clubName" to "Västra Klubben", "logoUrl" to "https://unsafe.test/logo"),
        ))
        compose.runOnIdle { badges.value = MemberAvatarBadges(SupporterBadge(), renamed) }
        compose.onNodeWithText(label()).assertDoesNotExist()
        compose.onNodeWithText(label(renamed.clubName)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("club_avatar_logo", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { badges.value = MemberAvatarBadges(SupporterBadge(), null) }
        compose.onNodeWithTag("profile_club_affiliation").assertDoesNotExist()
        compose.onNodeWithTag("club_avatar_badge").assertDoesNotExist()
        assertEquals(1, observations)
    }

    @Test fun longClubNameWrapsAtLargeTextWithTheExistingAccessibleLabel() {
        val longName = "Kungsbacka Car Community och Västra Sveriges Bilentusiaster"
        val longBadge = requireNotNull(ClubBadge.fromField(
            mapOf("clubId" to "club", "clubName" to longName, "logoUrl" to null),
        ))
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(320.dp)) { ProfileClubAffiliation(longBadge) }
                }
            }
        }
        compose.onNodeWithTag("profile_club_affiliation")
            .assertIsDisplayed().assertTextEquals(label(longName)).assertHasNoClickAction()
    }
}
