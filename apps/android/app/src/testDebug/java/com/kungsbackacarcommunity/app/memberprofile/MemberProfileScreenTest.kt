package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendRelationship
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import com.kungsbackacarcommunity.app.profile.ClubBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The member (other-user) profile: the Crown Points headline (the prominent
 * number directly under the nickname) + "Member since" stat shown for another
 * member, and the friend-gated Message + Unfriend actions (Seb, 2026-08). Awards
 * rendering itself is exercised by the badges tests; here we pin the headline +
 * section presence, the friend gating, and the empty/zero degradation.
 */
@RunWith(AndroidJUnit4::class)
class MemberProfileScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun loaded(
        pointsBalance: Long? = 150L,
        createdAtMillis: Long? = 1_700_000_000_000L,
        vehicles: List<Vehicle> = emptyList(),
        clubBadge: ClubBadge? = null,
    ) = MemberProfileState.Loaded(
        profile =
            MemberProfile(
                uid = "u2",
                displayName = "Ada",
                bio = "Volvo fan",
                createdAtMillis = createdAtMillis,
                clubBadge = clubBadge,
            ),
        vehicles = vehicles,
        badges = MemberBadges.Available(emptyList()),
        pointsBalance = pointsBalance,
    )

    private val affiliation = requireNotNull(ClubBadge.fromField(
        mapOf("clubId" to "club", "clubName" to "Östra Klubben", "logoUrl" to null),
    ))

    @Test
    fun publicClubNameIsVisibleRegardlessOfFriendRelationship() {
        val relationship = mutableStateOf<MemberFriendState?>(null)
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = loaded(clubBadge = affiliation), onRetry = {},
                    friendState = relationship.value,
                    onMessage = {}, onUnfriend = {},
                )
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.clubBadge_accessibility, affiliation.clubName)
        composeTestRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.friends_message)).assertDoesNotExist()
        for (value in listOf(
            FriendRelationship.Unknown, FriendRelationship.None, FriendRelationship.OutgoingPending,
            FriendRelationship.IncomingPending("request"), FriendRelationship.Friends,
        )) {
            composeTestRule.runOnIdle { relationship.value = MemberFriendState(relationship = value) }
            composeTestRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            if (value != FriendRelationship.Friends) {
                composeTestRule.onNodeWithText(str(R.string.friends_message)).assertDoesNotExist()
                composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle)).assertDoesNotExist()
            } else {
                composeTestRule.onNodeWithText(str(R.string.friends_message)).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test
    fun friendProfileShowsPointsMemberSinceAndTheMessageUnfriendBlockActions() {
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = loaded(),
                    onRetry = {},
                    onBlock = {},
                    friendState = MemberFriendState(relationship = FriendRelationship.Friends),
                    onUnfriend = {},
                    onMessage = {},
                )
            }
        }

        // Points: the prominent Crown Points headline under the nickname, showing
        // the public balance as a big number with its label.
        composeTestRule.onNodeWithText(str(R.string.profile_pointsTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("150").assertIsDisplayed()
        // Stats: the minimal "Member since" row.
        composeTestRule.onNodeWithText(str(R.string.memberProfile_statsMemberSince))
            .performScrollTo().assertIsDisplayed()

        // Relationship/menu and Message precede the garage; Block stays below content.
        composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.friends_message)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.blocking_blockUser)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingUnfriendConfirmsFirst() {
        var removed = 0
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = loaded(),
                    onRetry = {},
                    onBlock = {},
                    friendState = MemberFriendState(relationship = FriendRelationship.Friends),
                    onUnfriend = { removed++ },
                    onMessage = {},
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.friends_remove)).performClick()
        // The unfriend is confirm-guarded — the same copy the Friends screen uses.
        composeTestRule.onNodeWithText(str(R.string.friends_removeConfirmBody)).assertIsDisplayed()
        assertEquals(0, removed)
        composeTestRule.onNodeWithText(str(R.string.friends_removeConfirmAction)).performClick()
        assertEquals(1, removed)
    }

    @Test
    fun nonFriendProfileShowsBlockButNoMessageOrUnfriend() {
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = loaded(),
                    onRetry = {},
                    onBlock = {},
                    friendState = MemberFriendState(relationship = FriendRelationship.None),
                    onUnfriend = {},
                    onMessage = {},
                )
            }
        }

        composeTestRule.onNodeWithText(str(R.string.blocking_blockUser)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.friends_message)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.friends_remove)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle)).assertDoesNotExist()
    }

    private fun vehicles() = List(10) { index ->
        Vehicle(
            id = "local-car-$index", make = "Volvo", model = "240", modelYear = 1980 + index,
            powertrain = VehiclePowertrain.PETROL, engineDescription = null, isMainCar = index == 9,
        )
    }

    @Test
    fun largeTextRelationshipActionsLeadTheGarageAndKeepMessageCallback() {
        var messages = 0
        composeTestRule.setContent {
            KccTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) {
                        MemberProfileScreen(
                            state = loaded(vehicles = vehicles()), onRetry = {},
                            friendState = MemberFriendState(relationship = FriendRelationship.Friends),
                            onUnfriend = {}, onMessage = { messages++ },
                        )
                    }
                }
            }
        }
        val relationship = composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle))
        val message = composeTestRule.onNodeWithText(str(R.string.friends_message))
        val garage = composeTestRule.onNodeWithText(str(R.string.memberProfile_carsTitle))
        message.performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, messages)
        garage.performScrollTo()
        assertTrue(message.getUnclippedBoundsInRoot().top >= relationship.getUnclippedBoundsInRoot().bottom)
        assertTrue(garage.getUnclippedBoundsInRoot().top >= message.getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun mainCarLeadsTheCarouselAndTheLastPermittedCarIsReachable() {
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(state = loaded(vehicles = vehicles()), onRetry = {})
            }
        }
        composeTestRule.onNodeWithTag(MEMBER_VEHICLES_TAG).performScrollTo()
        composeTestRule.onNodeWithText("Volvo 240 (1989)").assertIsDisplayed()
        composeTestRule.onNodeWithTag(MEMBER_VEHICLES_TAG).performScrollToIndex(9)
        composeTestRule.onNodeWithText("Volvo 240 (1988)").assertIsDisplayed()
    }

    @Test
    fun busyFriendshipDisablesTheRemovalMenu() {
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = loaded(), onRetry = {}, onUnfriend = {}, onMessage = {},
                    friendState = MemberFriendState(
                        relationship = FriendRelationship.Friends, inFlight = FriendActionInFlight.Remove,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun blockedTransitionWithdrawsPublicCarsAndFriendActions() {
        val state = mutableStateOf<MemberProfileState>(loaded(vehicles = vehicles(), clubBadge = affiliation))
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = state.value, onRetry = {}, onUnfriend = {}, onMessage = {}, onUnblock = {},
                    friendState = MemberFriendState(relationship = FriendRelationship.Friends),
                )
            }
        }
        composeTestRule.runOnIdle { state.value = MemberProfileState.Blocked }
        composeTestRule.onNodeWithText(str(R.string.friends_message)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.shell_friendsTitle)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(MEMBER_VEHICLES_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag("profile_club_affiliation").assertDoesNotExist()
        composeTestRule.onNodeWithTag("club_avatar_badge").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.blocking_unblock)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unavailableAndFailedProfilesDoNotRetainTheLastPublicAffiliation() {
        val state = mutableStateOf<MemberProfileState>(loaded(clubBadge = affiliation))
        composeTestRule.setContent {
            KccTheme { MemberProfileScreen(state = state.value, onRetry = {}) }
        }
        composeTestRule.onNodeWithTag("profile_club_affiliation").performScrollTo().assertIsDisplayed()
        for (hidden in listOf(MemberProfileState.Unavailable, MemberProfileState.Error, MemberProfileState.Loading)) {
            composeTestRule.runOnIdle { state.value = hidden }
            composeTestRule.onNodeWithTag("profile_club_affiliation").assertDoesNotExist()
            composeTestRule.onNodeWithTag("club_avatar_badge").assertDoesNotExist()
        }
    }

    @Test
    fun aMemberWithNoPointsRendersZeroAndNoWalletHidesMemberSince() {
        composeTestRule.setContent {
            KccTheme {
                MemberProfileScreen(
                    state = loaded(pointsBalance = null, createdAtMillis = null),
                    onRetry = {},
                    onBlock = {},
                    friendState = MemberFriendState(relationship = FriendRelationship.None),
                )
            }
        }

        // Null balance degrades to a "0" headline.
        composeTestRule.onNodeWithText(str(R.string.profile_pointsTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
        // No join date → no "Member since" row at all (not an empty one).
        composeTestRule.onNodeWithText(str(R.string.memberProfile_statsMemberSince)).assertDoesNotExist()
    }
}
