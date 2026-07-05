package com.kungsbackacarcommunity.app.crownhunt

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
                    isActiveMember = false,
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
                    isActiveMember = true,
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
                    isActiveMember = true,
                    onCollect = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_errorLocationPermission)).assertIsDisplayed()
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
                    isActiveMember = true,
                    onCollect = {},
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_resultAwarded)).assertIsDisplayed()
    }
}
