package com.kungsbackacarcommunity.app.leaderboard

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

@RunWith(AndroidJUnit4::class)
class LeaderboardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactTable_retainsEveryPublishedRank_andScopeSelection() {
        var selected = LeaderboardScope.THIS_MONTH
        compose.setContent {
            KccTheme {
                LeaderboardScreen(
                    scope = LeaderboardScope.THIS_MONTH,
                    onScopeChange = { selected = it },
                    state = LeaderboardUiState.Loaded(
                        scope = LeaderboardScope.THIS_MONTH,
                        categories = listOf(
                            LeaderboardCategoryBoard(
                                LeaderboardCategory.CROWN_POINTS,
                                (1..4).map { rank -> LeaderboardEntry(rank, "u$rank", "Test driver $rank", null, rank * 100.0, rank == 4) },
                            ),
                        ),
                    ),
                )
            }
        }
        (1..4).forEach { rank ->
            compose.onNodeWithText("Test driver $rank").performScrollTo().assertIsDisplayed()
        }
        val allTime = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.leaderboard_scopeAllTime)
        compose.onNodeWithText(allTime).performScrollTo().performClick()
        assertEquals(LeaderboardScope.ALL_TIME, selected)
    }
}
