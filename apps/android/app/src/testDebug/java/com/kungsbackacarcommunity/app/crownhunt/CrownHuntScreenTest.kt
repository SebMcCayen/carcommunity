package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import com.kungsbackacarcommunity.app.points.PointsEntriesState
import com.kungsbackacarcommunity.app.points.PointsEntry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.BadgeTier
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroBackButtonTag
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

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

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
        // Overview uses this season's counts, not lifetime totals or wallet balance.
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_kpValue, 120)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_kpValue, 300)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("crownHubTab_2").performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_myStatsTitle)).assertIsDisplayed()
        // Season leaderboard block + a top scorer's name.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_leaderboardTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").performScrollTo().assertIsDisplayed()
        // The crown legend ("how they work") has MOVED off the hub into the
        // Instructions surface, so it is NOT on the hub any more (its new location is
        // asserted in instructionsButton_opensSurface_andBackArrowReturnsToHub).
        composeTestRule.onNodeWithText(str(R.string.crownHunt_legendTitle)).assertDoesNotExist()
        // The crowns-list collect button is gone.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_collectButton)).assertDoesNotExist()
    }

    @Test
    fun instructionsButton_opensSurface_andBackArrowReturnsToHub() {
        // NB: no LocalAeroBackAvailable is provided here — the Instructions surface
        // must provide it itself, so this proves the VISIBLE Aero Back arrow shows
        // on the surface without relying on the ambient routing context.
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loaded(personal = personal(), board = board()),
                    passesMemberGate = true,
                    onBack = {},
                )
            }
        }
        // Hub is shown first, and has no Back arrow of its own here.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statCrowns)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertDoesNotExist()
        // Tapping the Instructions button (by testTag) opens the rules surface.
        composeTestRule.onNodeWithTag(CrownHuntInstructionsButtonTag).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_instrIntroTitle)).assertIsDisplayed()
        // The crown legend ("how they work") now lives HERE, on the Instructions
        // surface (moved off the hub) — scroll it into view to prove it is present.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_legendTitle))
            .performScrollTo()
            .assertIsDisplayed()
        // The surface shows the pinned Aero Back arrow; tapping it returns to the
        // hub (via the BackHandler), not out of the route.
        composeTestRule.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statCrowns)).assertIsDisplayed()
    }

    @Test
    fun perksEnabled_openShop_seedsShopTab() {
        // With the perks flag on AND openShop requested (the perk-deploy popup's
        // "open the shop" link, issue #1009), the hub opens on the Shop tab: the
        // shop content renders and the Home stats block is NOT composed.
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loaded(personal = personal(), board = board()),
                    passesMemberGate = true,
                    onBack = {},
                    perksEnabled = true,
                    openShop = true,
                )
            }
        }
        // Shop tab content (default Loading shopState) is shown …
        composeTestRule.onNodeWithText(str(R.string.crownHunt_shopLoading)).assertIsDisplayed()
        // … and the Home tab's stats block is not composed.
        composeTestRule.onNodeWithText(str(R.string.crownHunt_myStatsTitle)).assertDoesNotExist()
    }

    @Test
    fun perksEnabled_default_opensOnHomeTab() {
        // Control: same flags but openShop = false (the default) opens on Home.
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loaded(personal = personal(), board = board()),
                    passesMemberGate = true,
                    onBack = {},
                    perksEnabled = true,
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statCrowns)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_shopLoading)).assertDoesNotExist()
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
        composeTestRule.onNodeWithTag("crownHubTab_2").performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_leaderboardEmpty)).performScrollTo().assertIsDisplayed()
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
        composeTestRule.onNodeWithTag("crownHubTab_2").performClick()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statsTitle)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("250", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun badgeStanding_noRungYet_showsCrownsProgressNotFirstCrownCopy() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(
                    statsState = CrownStatsUiState.Loaded(personal = personal(), board = board()),
                    passesMemberGate = true,
                    onBack = {},
                    kronjagare =
                        KronjagareStanding(
                            highestTier = null,
                            nextTier = BadgeTier.BRONS,
                            nextThresholdCrowns = 10L,
                            crownsCollected = 9L,
                        ),
                )
            }
        }
        composeTestRule.onNodeWithTag("crownHubTab_2").performClick()
        // The non-misleading "no rung yet" copy is shown (no "first crown"),
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statsRankNone)).performScrollTo().assertIsDisplayed()
        // and the honest progress figure against the goal line.
        composeTestRule.onNodeWithText("9 / 10", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun turningPerksOff_hidesAnAlreadyOpenShopImmediately() {
        val enabled = mutableStateOf(true)
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(CrownStatsUiState.Loaded(personal(), board()), true, {},
                    perksEnabled = enabled.value, openShop = true)
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_shopLoading)).assertIsDisplayed()
        composeTestRule.runOnIdle { enabled.value = false }
        composeTestRule.onNodeWithTag("crownHubTab_3").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_shopLoading)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_statCrowns)).assertIsDisplayed()
    }

    @Test
    fun mapTab_invokesExistingReturnCallback_withoutClaiming() {
        var returns = 0
        composeTestRule.setContent {
            KccTheme { CrownHuntScreen(CrownStatsUiState.Loading, true, { returns++ }) }
        }
        composeTestRule.onNodeWithTag("crownHubTab_1").performClick()
        assertEquals(1, returns)
    }

    @Test
    fun viewAll_opensRealLedgerHistory_includingDebits() {
        composeTestRule.setContent {
            KccTheme {
                CrownHuntScreen(CrownStatsUiState.Loaded(personal(), board()), true, {},
                    historyState = PointsEntriesState.Loaded(listOf(
                        PointsEntry("credit", 7, 100, "Actual credit", 200L),
                        PointsEntry("debit", -3, 93, "Actual debit", 100L),
                    )))
            }
        }
        composeTestRule.onNodeWithText("Actual credit").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Actual debit").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_viewAll)).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Actual debit").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_ledgerAmount, "-3")).assertIsDisplayed()
    }

}
