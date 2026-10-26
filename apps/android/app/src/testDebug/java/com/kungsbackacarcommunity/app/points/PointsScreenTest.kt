package com.kungsbackacarcommunity.app.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the points wallet (Phase 12 slice 15).
 */
@RunWith(AndroidJUnit4::class)
class PointsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test
    fun showsBalanceAndTransactions() {
        composeTestRule.setContent {
            KccTheme {
                PointsScreen(
                    balance = 125L,
                    entriesState = PointsEntriesState.Loaded(
                        listOf(PointsEntry("e1", 25, 125, "Kronjakt reward", 0L)),
                    ),
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_kpValue, 125)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Kronjakt reward").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_ledgerAmount, "+25")).assertIsDisplayed()
    }

    @Test
    fun nullBalance_showsLoading_withoutInventingZeroBalance() {
        composeTestRule.setContent {
            KccTheme {
                PointsScreen(
                    balance = null,
                    entriesState = PointsEntriesState.Loaded(emptyList()),
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.points_loading)).assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.points_empty)).assertIsDisplayed()
    }

    @Test
    fun debit_keepsSignedAmountAndDescription() {
        composeTestRule.setContent {
            KccTheme {
                PointsScreen(
                    balance = 100L,
                    entriesState = PointsEntriesState.Loaded(listOf(PointsEntry("debit", -25, 100, "Perk purchase", null))),
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_ledgerAmount, "-25")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Perk purchase").assertIsDisplayed()
    }

    @Test
    fun errorState_showsError() {
        composeTestRule.setContent {
            KccTheme {
                PointsScreen(
                    balance = 0L,
                    entriesState = PointsEntriesState.Error,
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.points_error)).assertIsDisplayed()
    }

    @Test
    fun filters_areLocalAndRetainTheAuthoritativeBalance() {
        composeTestRule.setContent {
            KccTheme {
                PointsScreen(91L, PointsEntriesState.Loaded(listOf(
                    PointsEntry("credit", 7, 98, "Credit fixture", 200L),
                    PointsEntry("debit", -7, 91, "Debit fixture", 100L),
                )), {})
            }
        }
        composeTestRule.onNodeWithText(str(R.string.crownHunt_ledgerCredits)).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Credit fixture").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Debit fixture").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_ledgerDebits)).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Debit fixture").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Credit fixture").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_ledgerBalanceAfter, 91)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.crownHunt_kpValue, 91)).performScrollTo().assertIsDisplayed()
    }
}
