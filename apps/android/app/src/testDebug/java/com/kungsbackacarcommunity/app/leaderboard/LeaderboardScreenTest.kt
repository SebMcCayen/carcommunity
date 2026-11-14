package com.kungsbackacarcommunity.app.leaderboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeaderboardScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tenthRank_fitsOnOneLine_withoutClipping_atLargerFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                KccTheme {
                    LeaderboardScreen(
                        scope = LeaderboardScope.ALL_TIME,
                        onScopeChange = {},
                        state = LeaderboardUiState.Loaded(
                            scope = LeaderboardScope.ALL_TIME,
                            categories = listOf(
                                LeaderboardCategoryBoard(
                                    LeaderboardCategory.CROWN_POINTS,
                                    (1..10).map { rank -> LeaderboardEntry(rank, "u$rank", "Driver $rank", null, 235.0, false) },
                                ),
                            ),
                        ),
                    )
                }
            }
        }
        val rank = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.leaderboard_rank, 10)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(rank, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(1, layout.lineCount)
        assertFalse("The full rank must fit inside its column", layout.hasVisualOverflow)
    }

    @Test
    fun categoryTabs_keepEveryPublishedCategoryReachable_withoutMixingTheirRows() {
        compose.setContent {
            KccTheme {
                LeaderboardScreen(LeaderboardScope.ALL_TIME, {}, LeaderboardUiState.Loaded(
                    scope = LeaderboardScope.ALL_TIME,
                    categories = LeaderboardCategory.entries.map { category ->
                        LeaderboardCategoryBoard(category, listOf(
                            LeaderboardEntry(1, "fixture", "Fixture ${category.name}", null, 8.0, false),
                        ))
                    },
                ))
            }
        }
        LeaderboardCategory.entries.forEach { category ->
            compose.onNodeWithTag("leaderboardCategory_${category.name}").performScrollTo().performClick()
            compose.onNodeWithText("Fixture ${category.name}").performScrollTo().assertIsDisplayed()
            LeaderboardCategory.entries.filter { it != category }.forEach { other ->
                compose.onNodeWithText("Fixture ${other.name}").assertDoesNotExist()
            }
        }
    }

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
