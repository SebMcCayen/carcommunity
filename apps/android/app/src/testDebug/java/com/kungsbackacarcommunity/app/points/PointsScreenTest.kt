package com.kungsbackacarcommunity.app.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

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
        composeTestRule.onNodeWithText("125").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kronjakt reward").assertIsDisplayed()
        composeTestRule.onNodeWithText("+25").assertIsDisplayed()
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
        composeTestRule.onNodeWithText("-25").assertIsDisplayed()
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
}
