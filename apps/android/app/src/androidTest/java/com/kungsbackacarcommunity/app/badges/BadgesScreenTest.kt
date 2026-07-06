package com.kungsbackacarcommunity.app.badges

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
 * Compose UI tests for the badges screen (Phase 12 slice 14).
 */
@RunWith(AndroidJUnit4::class)
class BadgesScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun empty_showsEmptyHint() {
        composeTestRule.setContent {
            KccTheme { BadgesScreen(state = BadgesState.Loaded(emptyList()), onBack = {}) }
        }
        composeTestRule.onNodeWithText(str(R.string.badges_emptyHint)).assertIsDisplayed()
    }

    @Test
    fun knownBadge_usesLocalizedName() {
        composeTestRule.setContent {
            KccTheme {
                BadgesScreen(
                    state = BadgesState.Loaded(
                        listOf(Badge("first_event", "raw fallback", 0L)),
                    ),
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText(str(R.string.badges_badgeNames_first_event)).assertIsDisplayed()
    }

    @Test
    fun unknownBadge_fallsBackToDocumentName() {
        composeTestRule.setContent {
            KccTheme {
                BadgesScreen(
                    state = BadgesState.Loaded(
                        listOf(Badge("mystery_badge", "Mystery Badge", 0L)),
                    ),
                    onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Mystery Badge").assertIsDisplayed()
    }
}
