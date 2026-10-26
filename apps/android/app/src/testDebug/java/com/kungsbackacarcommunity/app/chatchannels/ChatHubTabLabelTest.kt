package com.kungsbackacarcommunity.app.chatchannels

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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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

/** Measure the rendered strip, including scroll reachability, instead of an obsolete auto-shrink model. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatHubTabLabelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Variant(val width: Int, val scale: Float, val language: String, val dark: Boolean)

    @Test fun scaledLabelsStayReadableAndEveryChannelAndUnreadLabelIsReachable() {
        val variant = mutableStateOf(Variant(320, 1f, "sv", true))
        val selected = mutableStateOf(ChatTab.Community)
        var selectedCallback: ChatTab? = null
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
                KccTheme(darkTheme = current.dark) {
                    Box(Modifier.requiredWidth(current.width.dp)) {
                        ChatChannelStrip(selected.value, List(4) { true },
                            onSelect = { selectedCallback = it; selected.value = it }, onBack = null)
                    }
                }
            }
        }

        for (width in listOf(320, 390)) for (scale in listOf(1f, 1.5f)) {
            for (language in listOf("sv", "en")) for (dark in listOf(true, false)) {
                val current = Variant(width, scale, language, dark)
                compose.runOnIdle { variant.value = current }
                compose.waitForIdle()
                val config = Configuration(compose.activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                }
                val resources = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
                    applyOverrideConfiguration(config)
                }.resources
                val labelIds = listOf(R.string.chatHub_tabCommunity, R.string.chatHub_tabConvoys,
                    R.string.chatHub_tabFriends, R.string.chatHub_tabNotifications)
                for (tab in ChatTab.entries) {
                    val item = compose.onNodeWithTag(chatChannelTabTag(tab))
                    item.performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
                    compose.runOnIdle { assertEquals("$current callback", tab, selectedCallback) }
                    val bounds = item.fetchSemanticsNode().boundsInRoot
                    assertTrue("$current $tab hit height=$bounds", bounds.height >= 48f)
                    assertTrue("$current $tab hit width=$bounds", bounds.width >= 48f)
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(resources.getString(labelIds[tab.ordinal]), useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals("$current $tab layout", 1, layouts.size)
                    val layout = layouts.single()
                    assertEquals("$current $tab must not auto-shrink", 14.sp, layout.layoutInput.style.fontSize)
                    assertEquals(scale, layout.layoutInput.density.fontScale, 0.001f)
                    assertFalse("$current $tab truncated: size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
                        "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
                        layout.hasVisualOverflow)
                    // The dot remains a named child in every tab, including offscreen ones after scrolling.
                    compose.onNodeWithTag(chatChannelTabTag(tab)).assertIsDisplayed()
                    compose.onNode(
                        hasContentDescription(resources.getString(R.string.chatHub_tabUnread)) and
                            hasAnyAncestor(hasTestTag(chatChannelTabTag(tab))),
                        useUnmergedTree = true,
                    ).assertIsDisplayed()
                }
            }
        }
    }
}
