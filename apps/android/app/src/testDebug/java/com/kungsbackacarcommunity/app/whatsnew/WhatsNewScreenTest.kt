package com.kungsbackacarcommunity.app.whatsnew

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhatsNewScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun timeline_hasNamedHeadings_dates_andReachableChangesAtLargeText() {
        val entries = (3 downTo 1).map {
            ChangelogEntry(it, "test-$it", "2026-10-0$it", emptyList(),
                listOf("Fixture release change $it with enough text to wrap on a small screen."))
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                KccTheme { WhatsNewScreen(entries) }
            }
        }
        val title = InstrumentationRegistry.getInstrumentation().targetContext.getString(
            R.string.whatsNew_versionTitle, "test-3"
        )
        compose.onNodeWithText(title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("2026-10-03").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(entries.last().changes.single()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun emptyTimeline_showsEmptyCopy() {
        compose.setContent { KccTheme { WhatsNewScreen(emptyList()) } }
        val empty = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.whatsNew_empty)
        compose.onNodeWithText(empty).assertIsDisplayed()
    }
}
