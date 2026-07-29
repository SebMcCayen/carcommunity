package com.kungsbackacarcommunity.app.shell

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import com.kungsbackacarcommunity.app.location.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TalkBack accessibility of the live-session bar's merged [contentDescription].
 *
 * The bar draws glyphs and bare numbers — there is no width for word labels (see
 * [LiveSessionBar]'s KDoc) — so `semantics(mergeDescendants = true)` replaces the
 * whole row with ONE spoken sentence. That sentence is then the only thing a
 * screen-reader user has to reconstruct the bar by, which makes the order it
 * lists the metrics in load-bearing rather than cosmetic: if it spoke them in a
 * different order than they are drawn, a sighted member and a screen-reader
 * member looking at the same bar would be describing two different bars to each
 * other ("the middle number" would not mean the same thing to them).
 *
 * Nothing about that ordering is checkable by the compiler. `liveLocation_sessionBar`
 * takes POSITIONAL placeholders (`%1$s`…), so the argument list at the call site
 * and the localized strings can drift apart — or either can be reordered on its
 * own — and the result is a silently scrambled sentence in both languages, not a
 * build failure. This test is what closes that gap.
 *
 * ## Why it compares orders rather than asserting a fixed one
 * It measures where each metric is actually DRAWN (the leaf text nodes' left
 * edges, read from the unmerged semantics tree) and where each is SPOKEN (its
 * offset in the merged description), then asserts the two sequences agree. It
 * deliberately does not hard-code "speed, then distance": which metric sits
 * where is a design decision the bar is free to revisit, and a test that pinned
 * today's arrangement would just have to be rewritten alongside it — while
 * quietly failing to check the thing that must never change. Pinning the
 * RELATIONSHIP means any future reordering of the row keeps working only if the
 * sentence is reordered with it.
 */
@RunWith(AndroidJUnit4::class)
class LiveSessionBarSemanticsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private companion object {
        const val SESSION_START_MILLIS = 1_700_000_000_000L

        /** 67 s in, so the clock reads a two-part `1:07` rather than a bare `0:07`. */
        const val NOW_MILLIS = SESSION_START_MILLIS + 67_000L

        /** Over a kilometre, so the distance renders its `x.y km` form. */
        const val DISTANCE_METERS = 1234.0

        /** Exactly 54 km/h, so the expected readout involves no rounding. */
        const val SPEED_MPS = 15.0
        const val SPEED_KMH = 54
    }

    /** Where a leaf text node's left edge sits, in the root's coordinates. */
    private fun drawnAt(text: String): Float =
        composeTestRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left

    private fun renderBar() {
        composeTestRule.setContent {
            KccTheme {
                LiveSessionBar(
                    sessionStartMillis = SESSION_START_MILLIS,
                    distanceMeters = DISTANCE_METERS,
                    speedSample =
                        SpeedSample(
                            metersPerSecond = SPEED_MPS,
                            atMillis = NOW_MILLIS,
                        ),
                    now = { NOW_MILLIS },
                )
            }
        }
        // The displayed km/h is set from a LaunchedEffect, so the number is not on
        // screen at the end of the first composition.
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithText(SPEED_KMH.toString(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun theBarSpeaksItsMetricsInTheOrderItDrawsThem() {
        renderBar()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val elapsedText =
            LiveSessionFormat.elapsedLabel(
                LiveSessionElapsed.elapsedMillis(SESSION_START_MILLIS, NOW_MILLIS),
            )
        val distanceText = DriveFormatters.formatDistance(DISTANCE_METERS)
        // The speed is the one metric whose spoken form differs from its drawn
        // form: the bar draws `54` and speaks "current speed 54 km/h", because
        // the unit has no width on the strip.
        val spokenSpeed = context.getString(R.string.liveLocation_sessionBarSpeed, SPEED_KMH)

        val description =
            composeTestRule
                .onNodeWithTag(LIVE_SESSION_BAR_TEST_TAG)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(separator = " ")
                .orEmpty()

        val metrics =
            listOf(
                Metric("elapsed", description.indexOf(elapsedText), drawnAt(elapsedText)),
                Metric("speed", description.indexOf(spokenSpeed), drawnAt(SPEED_KMH.toString())),
                Metric("distance", description.indexOf(distanceText), drawnAt(distanceText)),
            )

        // A metric the sentence never mentions has no spoken position to compare,
        // and is its own accessibility bug, so say so plainly instead of letting
        // an index of -1 sort itself to the front.
        metrics.forEach {
            assertTrue(
                "The ${it.name} metric is drawn but missing from the bar's " +
                    "contentDescription: \"$description\"",
                it.spokenAt >= 0,
            )
        }

        assertEquals(
            "TalkBack announces the bar's metrics in a different order than the bar " +
                "draws them. Spoken sentence: \"$description\".",
            metrics.sortedBy { it.drawnAt }.map { it.name },
            metrics.sortedBy { it.spokenAt }.map { it.name },
        )
    }

    /**
     * One metric's two positions: [drawnAt] is its left edge on screen, [spokenAt]
     * its offset in the merged sentence. Only their ORDER is compared.
     */
    private data class Metric(
        val name: String,
        val spokenAt: Int,
        val drawnAt: Float,
    )
}
