package com.kungsbackacarcommunity.app.crownhunt

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w700dp-h900dp-mdpi")
class CrownHubTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Variant(val width: Int, val scale: Float, val language: String)

    @Test
    fun fullLabelsStayOnOneLine_withAdaptiveWidthsAndReadableLargeText() {
        val variant = mutableStateOf(Variant(320, 1f, "en"))
        val selected = mutableStateOf(0)
        compose.setContent {
            val current = variant.value
            val config = remember(current.language) {
                Configuration(compose.activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(current.language))
                }
            }
            val localized = remember(current.language) {
                ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
                    applyOverrideConfiguration(config)
                }
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalDensity provides Density(1f, current.scale),
            ) {
                KccTheme {
                    Box(Modifier.requiredWidth(current.width.dp)) {
                        CrownHubTabs(
                            listOf(
                                CrownHubTab(0, localized.getString(R.string.crownHunt_overviewTab)),
                                CrownHubTab(2, localized.getString(R.string.crownHunt_statisticsTab)),
                                CrownHubTab(3, localized.getString(R.string.crownHunt_tabShop)),
                            ), selected.value, { selected.value = it },
                        )
                    }
                }
            }
        }
        val variants = listOf(
            Variant(288, 1f, "en"), Variant(288, 1f, "sv"),
            Variant(358, 1.3f, "en"), Variant(288, 2f, "en"),
            Variant(288, 2f, "sv"), Variant(568, 2f, "en"),
        )
        for (current in variants) {
            compose.runOnIdle { variant.value = current }
            compose.waitForIdle()
            val initial = listOf(0, 2, 3).map {
                compose.onNodeWithTag("crownHubTab_$it").fetchSemanticsNode().boundsInRoot
            }
            // At ordinary scale the complete row fits even in a 320dp page's gutters.
            if (current.scale == 1f) {
                assertTrue("$current complete row fits", initial.last().right - initial.first().left <= current.width + 1f)
                for (id in listOf(0, 2, 3)) compose.onNodeWithTag("crownHubTab_$id").assertIsDisplayed()
            }
            for (id in listOf(0, 2, 3)) {
                val tab = compose.onNodeWithTag("crownHubTab_$id")
                tab.performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
                val bounds = tab.fetchSemanticsNode().boundsInRoot
                assertTrue("$current minimum hit height", bounds.height >= 48f)
                assertTrue("$current minimum hit width", bounds.width >= 48f)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("crownHubTab_label_$id", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.size)
                val layout = layouts.single()
                assertEquals("$current one line", 1, layout.lineCount)
                assertFalse("$current no ellipsis", layout.isLineEllipsized(0))
                assertFalse("$current no vertical clipping", layout.didOverflowHeight)
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
                assertTrue("$current full rendered label width", layout.getLineRight(0) <= layout.size.width + 0.5f)
                assertEquals(current.scale, layout.layoutInput.density.fontScale, 0.001f)
            }
        }
    }
}
