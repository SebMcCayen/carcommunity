package com.kungsbackacarcommunity.app.memberprofile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.friends.FriendRelationship
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The member (other-user) profile: the Points balance + "Member since" stat that
 * are now shown for another member, and the friend-gated Message + Unfriend
 * actions (Seb, 2026-08). Awards rendering itself is exercised by the badges
 * tests; here we pin the three-section presence, the friend gating, and the
 * empty/zero degradation.
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
    ) = MemberProfileState.Loaded(
        profile =
            MemberProfile(
                uid = "u2",
                displayName = "Ada",
                bio = "Volvo fan",
                createdAtMillis = createdAtMillis,
            ),
        vehicles = emptyList(),
        badges = MemberBadges.Available(emptyList()),
        pointsBalance = pointsBalance,
    )

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

        // Points: the shared Kronpoäng card, showing the public balance.
        composeTestRule.onNodeWithText(str(R.string.profile_pointsTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("150").assertIsDisplayed()
        // Stats: the minimal "Member since" row.
        composeTestRule.onNodeWithText(str(R.string.memberProfile_statsMemberSince))
            .performScrollTo().assertIsDisplayed()

        // Friend actions + Block, all in the bottom group.
        composeTestRule.onNodeWithText(str(R.string.friends_message)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.friends_remove)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.blocking_blockUser)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingUnfriendConfirmsFirst() {
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

        composeTestRule.onNodeWithText(str(R.string.friends_remove)).performScrollTo().performClick()
        // The unfriend is confirm-guarded — the same copy the Friends screen uses.
        composeTestRule.onNodeWithText(str(R.string.friends_removeConfirmBody)).assertIsDisplayed()
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

        // Null balance degrades to "0 p".
        composeTestRule.onNodeWithText(str(R.string.profile_pointsTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
        // No join date → no "Member since" row at all (not an empty one).
        composeTestRule.onNodeWithText(str(R.string.memberProfile_statsMemberSince)).assertDoesNotExist()
    }
}
