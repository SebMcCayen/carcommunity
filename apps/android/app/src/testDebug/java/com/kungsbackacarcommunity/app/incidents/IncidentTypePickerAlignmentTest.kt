package com.kungsbackacarcommunity.app.incidents

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

/** Geometry of the actual picker, including Road closed's differently sized wrapped lines. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class IncidentTypePickerAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swedishCategoryLinesStayCenteredAtLargeFontSize() = checkLabels("sv", true)
    @Test fun englishCategoryLinesStayCenteredAtLargeFontSize() = checkLabels("en", false)

    private fun checkLabels(language: String, dark: Boolean) {
        val config = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
            applyOverrideConfiguration(config)
        }
        var picked: IncidentType? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(1f, 2f),
            ) {
                KccTheme(darkTheme = dark) {
                    Box(Modifier.requiredWidth(320.dp)) {
                        IncidentTypePickerDialog(onPick = { picked = it }, onDismiss = {})
                    }
                }
            }
        }
        val labelIds = listOf(
            R.string.incidents_typeAccident, R.string.incidents_typeRoadwork,
            R.string.incidents_typeHazard, R.string.incidents_typePolice, R.string.incidents_typeRoadClosed,
        )
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        for ((type, labelId) in IncidentType.entries.zip(labelIds)) {
            val label = localized.getString(labelId)
            val target = compose.onNode(hasContentDescription(label) and button)
            target.performScrollTo().assertIsDisplayed()
            val card = target.fetchSemanticsNode().boundsInRoot
            assertTrue("48dp target retained", card.width >= 48f && card.height >= 48f)
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(2f, layout.layoutInput.density.fontScale, 0.001f)
            assertFalse("No vertical clipping", layout.didOverflowHeight)
            assertFalse("No horizontal clipping", layout.didOverflowWidth)
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            if (type == IncidentType.ROAD_CLOSED) assertTrue("Exercise the wrapped label", layout.lineCount >= 2)
            for (line in 0 until layout.lineCount) {
                val lineCenter = textBounds.left + (layout.getLineLeft(line) + layout.getLineRight(line)) / 2f
                assertEquals("$type line $line centered on its category card", card.center.x, lineCenter, 1f)
                assertFalse("No ellipsis", layout.isLineEllipsized(line))
            }
        }
        compose.onNode(hasContentDescription(localized.getString(R.string.incidents_typeRoadClosed)) and button)
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(IncidentType.ROAD_CLOSED, picked) }
    }
}
