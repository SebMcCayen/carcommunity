package com.kungsbackacarcommunity.app.navigation.turnbyturn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag

const val NAV_CHROME_TOP_REGION_TAG = "nav_chrome_top_region"
const val NAV_CHROME_BOTTOM_REGION_TAG = "nav_chrome_bottom_region"

/**
 * Transparent navigation chrome over the map. The top gets at most 45% of the
 * window; the bottom gets the space left by the top's actual measured viewport.
 * Both measurements happen in one pass, so even the first frame after content or
 * window changes has disjoint regions. Scroll clipping keeps overflowing children
 * inside their region; the unused middle stays available to map gestures.
 *
 * Bottom scrolling starts at the end to keep ETA/Exit visible in short windows.
 * The slots should wrap their content vertically, rather than request full height.
 */
@Composable
fun NavChromeRegions(
    modifier: Modifier = Modifier,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    val topScroll = rememberScrollState()
    val bottomScroll = rememberScrollState()
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Box(
                Modifier.fillMaxWidth().testTag(NAV_CHROME_TOP_REGION_TAG)
                    .verticalScroll(topScroll),
            ) { top() }
            Box(
                Modifier.fillMaxWidth().testTag(NAV_CHROME_BOTTOM_REGION_TAG)
                    .verticalScroll(bottomScroll, reverseScrolling = true),
            ) { bottom() }
        },
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val topRegion = measurables[0].measure(
            constraints.copy(minHeight = 0, maxHeight = (height * 0.45f).toInt()),
        )
        val bottomRegion = measurables[1].measure(
            constraints.copy(minHeight = 0, maxHeight = height - topRegion.height),
        )
        layout(constraints.maxWidth, height) {
            topRegion.placeRelative(0, 0)
            bottomRegion.placeRelative(0, height - bottomRegion.height)
        }
    }
}
