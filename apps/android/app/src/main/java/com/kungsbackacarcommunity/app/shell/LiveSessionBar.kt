package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import com.kungsbackacarcommunity.app.location.SpeedSample
import kotlinx.coroutines.delay

/** Test tag on the live-session bar. */
const val LIVE_SESSION_BAR_TEST_TAG = "live_session_bar"

/**
 * Full-width statistics dock above the map footer (S16). Time, current speed and
 * distance share every available pixel. Metric cells use their natural scaled
 * text width plus an equal share of spare space, rather than leaving slack at
 * the end or reserving half of the remaining width for each driving value.
 *
 * If the three cells cannot fit, they flow onto additional rows. The dock grows
 * vertically instead of shrinking type, hiding units or ellipsizing readings.
 * The merged TalkBack sentence follows the same elapsed → speed → distance order.
 *
 * The local ticker advances elapsed time and expires stale speed samples without
 * recomposing the shell. Speed remains the neutral, deadbanded platform reading;
 * distance remains this session's recorded total. No recording behaviour changes.
 *
 * @param now injectable clock for deterministic elapsed/speed tests.
 */
@Composable
fun LiveSessionBar(
    sessionStartMillis: Long,
    distanceMeters: Double,
    speedSample: SpeedSample?,
    modifier: Modifier = Modifier,
    now: () -> Long = { System.currentTimeMillis() },
) {
    // The once-a-second tick is scoped to THIS composable, so a running session
    // recomposes only the bar, not the whole shell. Re-keyed on the start moment
    // so a new session restarts the count from zero.
    //
    // It publishes the CLOCK READING rather than the elapsed time, because the
    // speed readout needs the same instant to age its sample against: one tick
    // now drives both, so a stale reading blanks itself on the next second
    // without a second ticker (and without the bar recomposing twice as often).
    val tickMillis by
        produceState(initialValue = now(), sessionStartMillis) {
            while (true) {
                value = now()
                delay(1000L)
            }
        }
    val elapsedText =
        LiveSessionFormat.elapsedLabel(
            LiveSessionElapsed.elapsedMillis(sessionStartMillis, tickMillis),
        )
    val distanceText = DriveFormatters.formatDistance(distanceMeters)
    // The displayed km/h is STATE, not a derivation, because the deadband makes it
    // depend on what is already on screen: [LiveSpeedReadout.displayKmh] is fed the
    // current value and returns either it (reading too close to bother) or a new
    // one. Recomputed on a new sample AND on every tick, so a reading that goes
    // stale falls back to the placeholder on its own.
    var shownSpeedKmh by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(speedSample, tickMillis) {
        shownSpeedKmh = LiveSpeedReadout.displayKmh(speedSample, tickMillis, shownSpeedKmh)
    }
    val speedKmh = shownSpeedKmh
    // Same "km/h" label the History/summary speeds render, appended to the
    // already-deadbanded whole km/h rather than re-rounding a raw m/s.
    val speedText = DriveFormatters.formatSpeedKmh(speedKmh)
    // TalkBack gets the full "current speed" sentence the visible readout has no
    // room to spell out; the unit itself is now on screen as well.
    val speedDescription =
        if (speedKmh != null) {
            stringResource(R.string.liveLocation_sessionBarSpeed, speedKmh)
        } else {
            stringResource(R.string.liveLocation_sessionBarSpeedUnknown)
        }
    // The arguments are in the order the row renders them — elapsed, then speed,
    // then distance — because a merged contentDescription is the ONLY thing a
    // TalkBack user has to reconstruct the layout by. If it spoke the metrics in a
    // different order than they are drawn, a sighted and a screen-reader user
    // reading the same bar would be describing two different bars to each other.
    // `sessionBar` uses positional placeholders, so this list and the localized
    // strings have to move TOGETHER: reordering only one of them silently
    // scrambles the sentence in both languages rather than failing to compile.
    // LiveSessionBarSemanticsTest pins the spoken order against the measured
    // on-screen order so it cannot drift apart again.
    val description =
        stringResource(
            R.string.liveLocation_sessionBar,
            elapsedText,
            speedDescription,
            distanceText,
        )
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = KccSpacing.s12)
                .testTag(LIVE_SESSION_BAR_TEST_TAG)
                .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
            // Navigation already scrolls the entire top-chrome region. Do not
            // introduce an unbounded nested scroller inside that parent.
            val scrolling = if (constraints.hasBoundedHeight) Modifier.verticalScroll(scroll) else Modifier
            LiveMetricLayout(
                modifier = Modifier.fillMaxWidth().then(scrolling)
                    .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            ) {
                LiveMetric(elapsedText, "live_session_elapsed") { LiveDot() }
                LiveMetric(speedText, "live_session_speed") {
                    Icon(painter = painterResource(R.drawable.ic_speedometer),
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                }
                LiveMetric(distanceText, "live_session_distance") {
                    Icon(imageVector = Icons.Filled.Straighten,
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Pack natural-width cells in reading order, then distribute each row's slack. */
@Composable
private fun LiveMetricLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val gap = KccSpacing.s2
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val spacing = gap.roundToPx()
        val rows = mutableListOf<MutableList<Pair<Int, Int>>>()
        var usedWidth = 0
        measurables.forEachIndexed { index, measurable ->
            val preferred = measurable.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(width)
            if (rows.isEmpty() || usedWidth + spacing + preferred > width) {
                rows.add(mutableListOf())
                usedWidth = 0
            }
            val row = rows.last()
            if (row.isNotEmpty()) usedWidth += spacing
            row.add(index to preferred)
            usedWidth += preferred
        }
        val measuredRows = rows.map { row ->
            val spare = (width - row.sumOf { it.second } - spacing * (row.size - 1)).coerceAtLeast(0)
            row.mapIndexed { position, (index, preferred) ->
                val extra = spare / row.size + if (position < spare % row.size) 1 else 0
                measurables[index].measure(Constraints.fixedWidth(preferred + extra))
            }
        }
        val rowHeights = measuredRows.map { row -> row.maxOf { it.height } }
        val contentHeight = rowHeights.sum() + spacing * (rows.size - 1).coerceAtLeast(0)
        val height = constraints.constrainHeight(contentHeight)
        layout(width, height) {
            var y = ((height - contentHeight) / 2).coerceAtLeast(0)
            measuredRows.forEachIndexed { index, row ->
                var x = 0
                row.forEach { cell ->
                    cell.placeRelative(x, y + (rowHeights[index] - cell.height) / 2)
                    x += cell.width + spacing
                }
                y += rowHeights[index] + spacing
            }
        }
    }
}

@Composable
private fun LiveMetric(value: String, tag: String, icon: @Composable () -> Unit) {
    Row(modifier = Modifier.testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1, Alignment.CenterHorizontally)) {
        icon()
        Text(text = value, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The "recording" dot. Decorative — the bar's merged [contentDescription] already
 * says a live session is running, so a second announcement would only repeat it.
 */
@Composable
private fun LiveDot() {
    Box(
        modifier =
            Modifier
                // s2 (8dp) is the smallest token on the scale and is exactly the
                // diameter this wants — a dot, not a control.
                .size(KccSpacing.s2)
                .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}
