package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeTier
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the Kronjakt screen (Phase 12 slice 16).
 */
@RunWith(AndroidJUnit4::class)
class CrownHuntScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun point() =
        CrownHuntPoint(
            id = "p1",
            title = "Torg-kronan",
            description = "By the square",
            rewardPoints = 50,
            latitude = 57.0,
            longitude = 12.0,
            geofenceRadiusMeters = 50.0,
        )

    @Test
    fun nonMember_seesGate() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    pointsState = CrownHuntPointsState.Loaded(emptyList()),
                    claimStatus = CrownHuntClaimStatus.Idle,
                    passesMemberGate = false,
                    onCollect = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.subscription_memberRequiredBody)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_collectButton)).assertDoesNotExist()
    }

    @Test
    fun member_seesPoints_andCollectReportsId() {
        var collected: String? = null
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    pointsState = CrownHuntPointsState.Loaded(listOf(point())),
                    claimStatus = CrownHuntClaimStatus.Idle,
                    passesMemberGate = true,
                    onCollect = { collected = it },
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Torg-kronan").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_collectButton)).performScrollTo().performClick()
        assertEquals("p1", collected)
    }

    @Test
    fun needsLocation_showsHint() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    pointsState = CrownHuntPointsState.Loaded(listOf(point())),
                    claimStatus = CrownHuntClaimStatus.NeedsLocation,
                    passesMemberGate = true,
                    onCollect = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_locationUnavailable)).assertIsDisplayed()
    }

    @Test
    fun member_withNoCrownsNearby_seesEmptyStateNotBlank() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    pointsState = CrownHuntPointsState.Loaded(emptyList()),
                    claimStatus = CrownHuntClaimStatus.Idle,
                    passesMemberGate = true,
                    onCollect = {},
                    onBack = {},
                )
            }
        }
        // The core fix: an empty nearby list shows a friendly explanation, not a
        // blank page, and never the collect button (nothing to collect).
        composeTestRule.onNodeWithText(str(R.string.crownHunt_emptyHeadline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_emptyBody)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_collectButton)).assertDoesNotExist()
    }

    @Test
    fun statsCard_showsKronjagareStandingAboveEmptyState() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    pointsState = CrownHuntPointsState.Loaded(emptyList()),
                    claimStatus = CrownHuntClaimStatus.Idle,
                    passesMemberGate = true,
                    onCollect = {},
                    onBack = {},
                    kronjagare =
                        KronjagareStanding(
                            highestTier = BadgeTier.SILVER,
                            nextTier = BadgeTier.GULD,
                            nextThresholdCrowns = 250L,
                        ),
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statsTitle)).assertIsDisplayed()
        // The 250-crown Guld goal is named; no fabricated crowns-collected count.
        composeTestRule.onNodeWithText("250", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_emptyHeadline)).assertIsDisplayed()
    }

    @Test
    fun awardedResult_showsAwardedMessage() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    pointsState = CrownHuntPointsState.Loaded(listOf(point())),
                    claimStatus = CrownHuntClaimStatus.Done(
                        ClaimOutcome(CrownHuntClaimResult.AWARDED, 50, 150),
                    ),
                    passesMemberGate = true,
                    onCollect = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_resultAwarded)).assertIsDisplayed()
    }
}
