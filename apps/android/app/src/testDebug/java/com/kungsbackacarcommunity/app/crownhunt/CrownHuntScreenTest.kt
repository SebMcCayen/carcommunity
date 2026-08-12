package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeTier
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the Kronjakt HUB screen — now a read-only stats + season
 * leaderboard page. The crown LIST is gone (crowns live on the map only), so
 * these assert the new shape: own stats, this season's top scores, and no
 * per-crown collect button anywhere on the page.
 */
@RunWith(AndroidJUnit4::class)
class CrownHuntScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun board() =
        CrownSeasonBoard(
            seasonId = "2026-08",
            rows =
                listOf(
                    CrownLeaderboardRow(1, "u1", "Alice", 500, 12, isViewer = false),
                    CrownLeaderboardRow(2, "me", "You", 300, 7, isViewer = true),
                ),
            viewerRank = 2,
        )

    private fun personal() =
        CrownPersonalStats(
            points = 300,
            crownsCollected = 7,
            seasonRank = 2,
            seasonPoints = 120,
            seasonCrowns = 3,
            byRarity = mapOf(CrownRarity.COMMON to 5, CrownRarity.RARE to 2),
            streakCurrent = 4,
            streakBest = 9,
            seasonsWon = 0,
            rarest = CrownRarity.RARE,
        )

    @Test
    fun nonMember_seesGate() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loading,
                    passesMemberGate = false,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.subscription_memberRequiredBody)).assertIsDisplayed()
        // No collect button exists anywhere on this page any more.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_collectButton)).assertDoesNotExist()
    }

    @Test
    fun loaded_showsOwnStatsAndSeasonLeaderboard() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loaded(personal = personal(), board = board()),
                    passesMemberGate = true,
                    onBack = {},
                )
            }
        }
        // Own stats block.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_myStatsTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statCrowns)).assertIsDisplayed()
        // Season leaderboard block + a top scorer's name.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_leaderboardTitle)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        // The crown legend (types + "does it disappear?" answer) is present.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_legendTitle)).assertIsDisplayed()
        // The crowns-list collect button is gone.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_collectButton)).assertDoesNotExist()
    }

    @Test
    fun loaded_withNoPersonalStats_showsInvitationNotZeros() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState =
                        CrownStatsUiState.Loaded(
                            personal = null,
                            board = CrownSeasonBoard("2026-08", emptyList(), viewerRank = null),
                        ),
                    passesMemberGate = true,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_noStatsYet)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_leaderboardEmpty)).assertIsDisplayed()
    }

    @Test
    fun error_showsErrorMessage() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Error,
                    passesMemberGate = true,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statsError)).assertIsDisplayed()
    }

    @Test
    fun badgeStanding_stillShownAboveStats() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loaded(personal = personal(), board = board()),
                    passesMemberGate = true,
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
        composeTestRule.onNodeWithText("250", substring = true).assertIsDisplayed()
    }
}
