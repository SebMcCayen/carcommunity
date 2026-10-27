package com.kungsbackacarcommunity.app.chatchannels

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** Real header geometry/selection; pixel transparency is tested separately on native Android. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatFeedSelectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swedishDarkNormalTabsAreCompact() = checkHeader(320, 1f, "sv", true)
    @Test fun englishLightNormalTabsAreCompact() = checkHeader(390, 1f, "en", false)
    @Test fun swedishLargeTextRetainsFullLabelsAndTouchTargets() = checkHeader(320, 2f, "sv", true)
    @Test fun englishLargeTextRetainsFullLabelsAndTouchTargets() = checkHeader(320, 2f, "en", false)

    private fun checkHeader(width: Int, scale: Float, language: String, dark: Boolean) {
        val config = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
            applyOverrideConfiguration(config)
        }
        val selected = mutableStateOf(false)
        val callbacks = mutableListOf<Boolean>()
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(1f, scale),
            ) {
                KccTheme(darkTheme = dark) {
                    Box(Modifier.requiredWidth(width.dp)) {
                        ChatFeedHeader(selected.value, onSelect = {
                            callbacks += it
                            selected.value = it
                        })
                    }
                }
            }
        }

        val sourceLabels = listOf(false to R.string.chatHub_tabChats, true to R.string.chatHub_tabDriver)
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        fun tab(driver: Boolean) = compose.onNode(
            hasText(localized.getString(if (driver) R.string.chatHub_tabDriver else R.string.chatHub_tabChats)) and tabRole,
        )
        tab(false).assertIsSelected()
        tab(true).assertIsNotSelected()

        // Physical taps also prove the 48dp semantics bounds are real hit targets.
        for (next in listOf(true, false)) {
            tab(next).assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(next, callbacks.last()) }
            val selector = compose.onNodeWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).fetchSemanticsNode()
            assertFalse("No horizontal scrolling", selector.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
            if (scale == 1f) assertEquals("48dp targets plus symmetric 4dp gaps", 56f, selector.boundsInRoot.height, 0.01f)
            var previousRight = selector.boundsInRoot.left
            for ((driver, labelId) in sourceLabels) {
                val target = tab(driver)
                if (driver == next) target.assertIsSelected() else target.assertIsNotSelected()
                val bounds = target.fetchSemanticsNode().boundsInRoot
                assertTrue("48dp target: $bounds", bounds.width >= 48f && bounds.height >= 48f)
                assertTrue("Separate targets", bounds.left >= previousRight)
                previousRight = bounds.right
                assertEquals("Small top gap", 4f, bounds.top - selector.boundsInRoot.top, 0.01f)
                assertEquals("Small bottom gap", 4f, selector.boundsInRoot.bottom - bounds.bottom, 0.01f)

                val label = localized.getString(labelId)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("No font shrinking", 14.sp, layout.layoutInput.style.fontSize)
                assertEquals(scale, layout.layoutInput.density.fontScale, 0.001f)
                assertEquals(if (driver == next) TextDecoration.Underline else TextDecoration.None,
                    layout.layoutInput.style.textDecoration)
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                assertFalse("No vertical clipping", layout.didOverflowHeight)
                assertFalse("No horizontal clipping", layout.didOverflowWidth)
                for (line in 0 until layout.lineCount) assertFalse("No ellipsis", layout.isLineEllipsized(line))
            }
        }
        compose.runOnIdle { assertEquals(listOf(true, false), callbacks) }
    }
}
