package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import com.kungsbackacarcommunity.app.location.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w650dp-h1000dp-mdpi")
class LiveSessionBarLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Variant(val width: Int, val scale: Float, val longValues: Boolean)

    @Test fun valuesAndUnitsStayWholeAndUseEveryRowAtNarrowWidthsAndLargeText() {
        val variant = mutableStateOf(Variant(350, 1f, false))
        var expectedFontSize = TextUnit.Unspecified
        val now = 1_700_000_000_000L
        compose.setContent {
            val current = variant.value
            CompositionLocalProvider(LocalDensity provides Density(1f, current.scale)) {
                KccTheme {
                    expectedFontSize = androidx.compose.material3.MaterialTheme.typography.titleSmall.fontSize
                    Box(Modifier.requiredWidth(current.width.dp)) {
                        LiveSessionBar(
                            sessionStartMillis = now - if (current.longValues) 21_540_000L else 754_000L,
                            distanceMeters = if (current.longValues) 1_234_500.0 else 12_400.0,
                            speedSample = SpeedSample(if (current.longValues) 36.0 else 15.0, now),
                            now = { now },
                        )
                    }
                }
            }
        }
        for (width in listOf(280, 350, 600)) for (scale in listOf(1f, 1.5f, 2f)) {
            for (longValues in listOf(false, true)) {
                val current = Variant(width, scale, longValues)
                compose.runOnIdle { variant.value = current }
                compose.waitForIdle()
                val elapsed = LiveSessionFormat.elapsedLabel(if (longValues) 21_540_000L else 754_000L)
                val speed = DriveFormatters.formatSpeedKmh(if (longValues) 130 else 54)
                val distance = DriveFormatters.formatDistance(if (longValues) 1_234_500.0 else 12_400.0)
                val bar = compose.onNodeWithTag(LIVE_SESSION_BAR_TEST_TAG).fetchSemanticsNode().boundsInRoot
                assertEquals("$current full bar width", width.toFloat(), bar.width, 0.5f)
                for (text in listOf(elapsed, speed, distance)) {
                    val node = compose.onNodeWithText(text, useUnmergedTree = true)
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    assertEquals("$current $text original typography", expectedFontSize, layout.layoutInput.style.fontSize)
                    // A cached paragraph can retain a wider allocation than the
                    // current text node (didOverflowWidth then gives a false positive).
                    // Assert actual glyph bounds and full text coverage instead.
                    assertFalse("$current $text height overflow", layout.didOverflowHeight)
                    assertEquals("$current $text complete content", text.length,
                        layout.getLineEnd(layout.lineCount - 1))
                    for (line in 0 until layout.lineCount) {
                        assertFalse("$current $text ellipsis", layout.isLineEllipsized(line))
                        assertTrue("$current $text glyphs fit line $line",
                            layout.getLineLeft(line) >= -0.5f && layout.getLineRight(line) <= layout.size.width + 0.5f)
                    }
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    assertTrue("$current $text inside bar", bounds.left >= bar.left && bounds.right <= bar.right &&
                        bounds.top >= bar.top && bounds.bottom <= bar.bottom)
                }
                val cells = listOf("elapsed", "speed", "distance").map {
                    compose.onNodeWithTag("live_session_$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                }
                // Every row extends between the same horizontal padding; no unused
                // trailing area is stranded while a different readout is cramped.
                cells.groupBy { it.center.y }.values.forEach { row ->
                    assertEquals("$current row starts at padding", bar.left + 16f, row.first().left, 0.5f)
                    assertEquals("$current row fills width", bar.right - 16f, row.last().right, 0.5f)
                }
                assertTrue("$current reading order", cells.zipWithNext().all { (a, b) ->
                    b.top > a.top || (b.top == a.top && b.left >= a.right)
                })
                if (width == 600 && scale == 1f) assertTrue("roomy bar remains one row", bar.height <= 48f)
                if (width == 280 && scale == 2f) assertTrue("large type gets room vertically", bar.height > 48f)
            }
        }
    }

    @Test fun aShortWindowCanScrollEveryWholeReadoutIntoView() {
        val now = 21_540_000L
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                KccTheme {
                    Box(Modifier.requiredWidth(280.dp).height(72.dp)) {
                        LiveSessionBar(0L, 1_234_500.0, SpeedSample(36.0, now), now = { now })
                    }
                }
            }
        }
        compose.waitForIdle()
        for (text in listOf(LiveSessionFormat.elapsedLabel(now), DriveFormatters.formatSpeedKmh(130),
            DriveFormatters.formatDistance(1_234_500.0))) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            node.performScrollTo().assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertFalse("$text still has full height", layout.didOverflowHeight)
            assertEquals("$text fits fully in the scrolled viewport", layout.size.height.toFloat(),
                node.fetchSemanticsNode().boundsInRoot.height, 0.5f)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

}
