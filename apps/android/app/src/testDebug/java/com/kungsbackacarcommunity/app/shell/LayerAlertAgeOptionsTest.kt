package com.kungsbackacarcommunity.app.shell

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.incidents.IncidentAgeFilter
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class LayerAlertAgeOptionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Variant(val width: Int, val scale: Float, val language: String, val dark: Boolean)

    @Test fun choicesFitWithoutScrollingAndApplyInAscendingOrderAtLargeTextSizes() {
        // 256dp models a 320dp phone after the sheet and content side padding.
        val variant = mutableStateOf(Variant(256, 1f, "sv", true))
        val selection = mutableStateOf(IncidentAgeOption.ALL)
        val changes = mutableListOf<IncidentAgeOption>()
        compose.setContent {
            val current = variant.value
            val config = remember(current.language) {
                Configuration(compose.activity.resources.configuration).apply { setLocale(Locale(current.language)) }
            }
            val localized = remember(current.language) {
                ContextThemeWrapper(compose.activity, compose.activity.theme).apply { applyOverrideConfiguration(config) }
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(1f, current.scale)) {
                KccTheme(darkTheme = current.dark) {
                    Box(Modifier.requiredWidth(current.width.dp)) {
                        LayerAlertAgeOptions(selection.value) { changes.add(it); selection.value = it }
                    }
                }
            }
        }
        for (width in listOf(256, 326, 390)) for (scale in listOf(1f, 1.5f, 2f)) {
            for (language in listOf("sv", "en")) for (dark in listOf(false, true)) {
                val current = Variant(width, scale, language, dark)
                compose.runOnIdle { variant.value = current; changes.clear() }
                compose.waitForIdle()
                val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale(language)) }
                val resources = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
                    applyOverrideConfiguration(config)
                }.resources
                val root = compose.onNodeWithTag(MAP_HOME_LAYERS_ALERT_AGE_TAG).fetchSemanticsNode()
                assertFalse(root.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
                val bounds = root.boundsInRoot
                val ids = listOf(R.string.shell_layersAlertAge3d, R.string.shell_layersAlertAge1w,
                    R.string.shell_layersAlertAge30d, R.string.shell_layersAlertAgeAll)
                var previousTop = bounds.top
                for ((index, option) in IncidentAgeFilter.orderedOptions.withIndex()) {
                    val node = compose.onNodeWithTag("${MAP_HOME_LAYERS_ALERT_AGE_TAG}_${option.name}")
                    node.assertIsDisplayed().performTouchInput { click() }
                    node.assertIsSelected()
                    val target = node.fetchSemanticsNode().boundsInRoot
                    assertTrue("$current $option hit target $target", target.width >= 48 && target.height >= 48)
                    assertTrue("$current $option inside $bounds", target.left >= bounds.left && target.right <= bounds.right &&
                        target.top >= bounds.top && target.bottom <= bounds.bottom)
                    assertTrue("$current options stay ordered", target.top >= previousTop)
                    previousTop = target.top
                    val layouts = mutableListOf<TextLayoutResult>()
                    val label = resources.getString(ids[index])
                    compose.onNodeWithText(label, useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    assertEquals(scale, layout.layoutInput.density.fontScale, 0.001f)
                    assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                    assertFalse("$current $option vertical clipping", layout.didOverflowHeight)
                    assertFalse("$current $option horizontal clipping", layout.didOverflowWidth)
                }
                compose.runOnIdle { assertEquals(IncidentAgeFilter.orderedOptions, changes) }
            }
        }
    }
}
