package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import com.kungsbackacarcommunity.app.location.SpeedSample
import kotlinx.coroutines.delay

/** Test tag on the live-session bar. */
const val LIVE_SESSION_BAR_TEST_TAG = "live_session_bar"

/**
 * The floor BOTH driving readouts (speed and distance) are allowed to auto-shrink
 * to before they give up and ellipsize. The bar's normal metric size is
 * `titleSmall` (16sp here), so this leaves a wide shrink range; it is a legibility
 * floor, not a target — a driving readout smaller than this is not worth showing
 * whole, so below it the text ellipsizes rather than becoming unreadable. Paired
 * with [LIVE_METRIC_FONT_STEP] and a max of the live style's own size, so at any
 * width that already fits nothing shrinks and the bar looks exactly as it did.
 */
private val LIVE_METRIC_MIN_FONT_SIZE = 10.sp

/** The granularity the driving readouts step their font down by to find the fit. */
private val LIVE_METRIC_FONT_STEP = 0.5.sp

/**
 * A frosted bar shown at the top of the map — filling the strip between the
 * search icon and the profile avatar — WHILE a live-sharing session is running.
 * It reports how long the session has run, how fast the member is going right
 * now, and how far the session has driven. It stretches to fill the whole gap
 * the search control and avatar frame (the same full-width treatment the convoy
 * bar got in #536).
 *
 * ## One left-aligned group, not opposite ends
 * The readouts used to be pushed to the bar's two ends
 * (`Arrangement.SpaceBetween`), which put a wide empty gap between a `0:07` and a
 * `1,2 km` — the bar read as two unrelated widgets rather than one status line.
 * They are now a single left-aligned group: a live dot, the running time, a
 * hairline separator, then the current speed and the distance. Ragged space falls
 * at the END of the bar, where the eye expects it, and the group stays put as the
 * numbers grow instead of the gap breathing in and out once a second.
 *
 * The rule separates the CLOCK from the two DRIVING numbers; speed and distance
 * then sit together with only their glyphs between them. A second rule would be
 * the tidier grouping but there is no width for it — see the budget below.
 *
 * The leading dot is the recording affordance — the same `error` red as the
 * shell's STOP disc, so the two read as one state — and it replaces the clock
 * glyph the time used to carry: `● 0:07` is already unmistakably a running timer,
 * and dropping a glyph buys back width for long values. It is deliberately NOT
 * pulsing: an infinite animation keeps the Compose test clock from ever going
 * idle, which would hang every instrumented test that touches this screen.
 *
 * ## Icons + numbers, never labels
 * The bar sits between the search control and the avatar and never crowds
 * either, so it carries numbers and glyphs — no "elapsed" or "distance" words that
 * would overflow the strip (the same reason the convoy bar shows a bare count).
 * The full sentence is kept as the bar's [contentDescription] for TalkBack. At a
 * narrow width (or a long value — a 3-digit `km/h`, a long `km`, an `Hh MMm`
 * clock) the two DRIVING numbers are the parts that give: speed AND distance each
 * AUTO-SHRINK their font to stay whole and only ellipsize as a last resort below
 * the legibility floor, so the running time — the one that changes every second —
 * is never the thing that gets cut.
 *
 * ### The speed readout carries a visible "km/h"
 * The number is drawn with its unit — `54 km/h` — beside the speedometer glyph,
 * the same "km/h" label the History and drive-summary speeds render (via
 * [DriveFormatters]). The strip's width is tight — the bar is handed
 * `screen − 2×16 (page padding) − 48+8 (search) − 48+8 (avatar)` and spends
 * another 2×16 on its own padding: 184dp of content on a 360dp phone, 144dp on a
 * 320dp one — so both driving numbers are allowed to give: the clock stays whole
 * while speed and distance SHARE the leftover width (each carries
 * `weight(fill = false)`) and each auto-shrinks its font to fit its share — see
 * [SpeedMetric] and [DistanceMetric]. Neither is pinned to a fixed width, so as
 * either number grows it sizes to its content and shrinks rather than clipping off
 * the right edge of the bar.
 *
 * ### Neutral by construction
 * The speed is a plain readout of the member's own current speed. It is never
 * ranked, scored, remembered as a best, compared with anyone, or coloured to
 * suggest fast or slow — it renders in the same `onSurface` as the clock and the
 * distance, always. See [LiveSpeedReadout].
 *
 * ## Where the numbers come from
 * - Elapsed is `now − [sessionStartMillis]`, and which instant that start is
 *   is [LiveSessionElapsed]'s job — the host hands down an already-latched,
 *   device-clock anchor, so the first frame reads `0:00` and only ever counts up.
 *   The per-second ticker lives HERE, inside the bar, on purpose: reading a
 *   once-a-second state up in the large host composable would recompose the whole
 *   shell every second, so only this small bar re-reads the clock. The timer
 *   advances even while the car (and the GPS stream) is stationary, because it
 *   re-reads [now] rather than the last fix.
 * - [speedSample] is the platform's own `Location.speed` off the latest fix
 *   ([com.kungsbackacarcommunity.app.location.CurrentSpeed]), turned into a whole
 *   km/h by [LiveSpeedReadout]. It is deliberately NOT derived from successive
 *   positions. A null sample — and a sample that has gone stale, which the
 *   per-second tick re-checks — renders the placeholder rather than a `0` that
 *   would claim the car had stopped.
 * - [distanceMeters] is the drive recorder's running total for THIS session —
 *   the same value History would save — formatted with [DriveFormatters]. It is
 *   hoisted (it changes only on GPS fixes, which the host already observes).
 *
 * The host composes this only while a session is active; there is no "idle"
 * variant, so it never renders a zeroed-out placeholder.
 *
 * @param speedSample the latest ground-speed reading, or null when none has
 *   arrived. Hoisted like [distanceMeters] — it changes only on GPS fixes.
 * @param now the clock the ticker reads, injectable so the elapsed readout is
 *   deterministic in tests. Defaults to the wall clock.
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
                // Fill the weighted strip the shell hands us (search button -> avatar)
                // and match the flanking 48dp controls' height, so the bar reads as a
                // single full-width band rather than a short pill — mirroring the
                // convoy bar's own `fillMaxWidth` treatment.
                .fillMaxWidth()
                .height(KccSpacing.s12)
                .testTag(LIVE_SESSION_BAR_TEST_TAG)
                .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            // One left-aligned group. Any slack lands at the END of the bar rather
            // than as a hole between the two numbers.
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            LiveDot()
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            MetricSeparator()
            // Speed and distance SHARE the width left after the clock: each carries
            // `weight(1f, fill = false)`, so between them they can never claim more
            // than that leftover — nothing is pushed off the right edge — and each
            // auto-shrinks its font to fit its share rather than clipping. `fill =
            // false` keeps them at their content width when the strip is wide, so at
            // a normal width both render at full size exactly as before.
            SpeedMetric(
                value = speedText,
                modifier = Modifier.weight(1f, fill = false),
            )
            DistanceMetric(
                value = distanceText,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
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

/** Hairline rule between the two readouts, so they group without crowding. */
@Composable
private fun MetricSeparator() {
    Box(
        modifier =
            Modifier
                // 1dp is a hairline rule: below the spacing scale's smallest step
                // by design (KccSpacing.s1 = 4dp would be a bar, not a rule).
                .width(1.dp)
                .height(KccSpacing.s4)
                // `outline`, NOT Material's usual divider role `outlineVariant`:
                // this theme maps outlineVariant to darkCharcoal in the dark
                // scheme, which is the very same colour as its `surface`
                // (KccTheme.kt) — the rule would be invisible on this bar.
                .background(MaterialTheme.colorScheme.outline),
    )
}

/**
 * The auto-size behaviour BOTH driving readouts share. Steps the font down from
 * the bar's normal `titleSmall` size to [LIVE_METRIC_MIN_FONT_SIZE] in
 * [LIVE_METRIC_FONT_STEP] increments to find the largest size that fits the width
 * the readout is handed. Max = the live style's own size, so at any width that
 * already fits nothing shrinks and the bar looks exactly as it did; the readout
 * only steps down when its share of the width forces it, never below the
 * legibility floor. The auto-size is purely visual — the spoken
 * [contentDescription] the bar merges is unaffected.
 */
@Composable
private fun liveMetricAutoSize(): TextAutoSize =
    TextAutoSize.StepBased(
        minFontSize = LIVE_METRIC_MIN_FONT_SIZE,
        maxFontSize = MaterialTheme.typography.titleSmall.fontSize.takeOrElse { 16.sp },
        stepSize = LIVE_METRIC_FONT_STEP,
    )

/**
 * The speed readout: speedometer glyph + a whole number of km/h WITH its unit,
 * e.g. `54 km/h` (or the missing value dash). The unit is drawn here and also
 * spoken in full by the bar's [contentDescription] — see [LiveSessionBar]'s KDoc.
 *
 * Like [DistanceMetric], the number AUTO-SHRINKS its font (via [liveMetricAutoSize])
 * to fit the share of the width the row hands it, so crossing 9 → 10 → 100 km/h
 * sizes to its content and stays whole rather than clipping off the right of the
 * bar. It is deliberately NOT pinned to a fixed reservation width: a reservation
 * would keep the distance from shuffling as the number grew, but it also forced
 * the readout to clip when the strip was tight, which is the very bug this fixes.
 *
 * Same 18dp glyph size and `primary` tint as [DistanceMetric], so the two read as
 * one pair of metrics rather than one shouting louder than the other. The number
 * itself is the same `onSurface` as everything else in the bar at every speed —
 * there is deliberately no threshold at which it changes colour.
 */
@Composable
private fun SpeedMetric(
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_speedometer),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            // Matches DistanceMetric's dense 18dp exactly.
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            autoSize = liveMetricAutoSize(),
            // Last resort only: a value that will not fit even at the floor
            // ellipsizes rather than shrinking into illegibility.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The distance readout: glyph + number. Like [SpeedMetric], the number
 * AUTO-SHRINKS its font (via [liveMetricAutoSize]) to fit the share of the width
 * the row hands it (`weight(fill = false)` — see [LiveSessionBar]) rather than
 * clipping, so at any width that already fits it renders exactly as before and at
 * a narrow width it stays whole and legible. The ellipsis is kept only as the last
 * resort for the pathological case where even the floor will not fit.
 */
@Composable
private fun DistanceMetric(
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        Icon(
            imageVector = Icons.Filled.Straighten,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            // 18dp: the "dense" icon size Material uses inside compact bars —
            // between the 24dp default (too heavy next to titleSmall) and the
            // spacing scale, which has no icon step.
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            autoSize = liveMetricAutoSize(),
            // Last resort only: a value that will not fit even at the floor
            // ellipsizes rather than shrinking into illegibility.
            overflow = TextOverflow.Ellipsis,
        )
    }
}
