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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import com.kungsbackacarcommunity.app.location.SpeedSample
import kotlinx.coroutines.delay

/** Test tag on the live-session bar. */
const val LIVE_SESSION_BAR_TEST_TAG = "live_session_bar"

/**
 * The widest string the speed readout can ever render, used ONLY to reserve
 * width — never drawn visibly. Three digits covers every speed a road vehicle
 * reaches, and reserving them means the readout does not change width as the
 * number crosses 9 → 10 → 100, so the distance beside it never shuffles
 * sideways while driving. Zeros rather than any other digit because Roboto's
 * figures are tabular (equal advance), so any three digits measure the same.
 */
private const val SPEED_WIDTH_RESERVATION = "000"

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
 * narrow width (or a long value — a 3-digit `km`, an `Hh MMm` clock) the DISTANCE
 * is the part that gives: it may shrink and ellipsize, so the running time — the
 * one that changes every second — is never the thing that gets cut.
 *
 * ### The speed readout carries no visible "km/h", and why
 * The strip's width is fully spoken for. The bar is handed
 * `screen − 2×16 (page padding) − 48+8 (search) − 48+8 (avatar)`, and spends
 * another 2×16 on its own padding: 184dp of content on a 360dp phone, 144dp on a
 * 320dp one. The existing dot + clock + rule + distance already come to ~128dp,
 * so a glyph (18) + gap (4) + three reserved digits (~24) + the row gap (8) is
 * the entire remaining budget. A visible "km/h" (~27dp at labelSmall) would push
 * the distance into ellipsizing on a mainstream 360dp device, which is too high a
 * price for a unit this app has exactly one of. The unit is therefore spoken, not
 * drawn: the speedometer glyph names the quantity, and the bar's TalkBack sentence
 * says "current speed 54 km/h" in full.
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
    val speedText = speedKmh?.toString() ?: DriveFormatters.MISSING_VALUE
    // TalkBack gets the unit and the word the visible readout has no width for.
    val speedDescription =
        if (speedKmh != null) {
            stringResource(R.string.liveLocation_sessionBarSpeed, speedKmh)
        } else {
            stringResource(R.string.liveLocation_sessionBarSpeedUnknown)
        }
    val description =
        stringResource(
            R.string.liveLocation_sessionBar,
            elapsedText,
            distanceText,
            speedDescription,
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
            // Fixed width by construction, so it goes BEFORE the one child that is
            // allowed to shrink — the ragged edge then stays where it always was,
            // at the end of the row.
            SpeedMetric(value = speedText)
            DistanceMetric(
                value = distanceText,
                // Only the distance may shrink: at a narrow width the running clock
                // and the speed stay whole and this ellipsizes instead.
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
 * The speed readout: speedometer glyph + a whole number of km/h (or the missing
 * value dash). The unit is spoken by the bar's [contentDescription], not drawn —
 * see the width budget in [LiveSessionBar]'s KDoc.
 *
 * The number sits in a Box over an INVISIBLE [SPEED_WIDTH_RESERVATION], which is
 * the whole trick to a stable layout: laid out but never drawn, it fixes the
 * readout at its widest, so crossing 9 → 10 → 100 km/h (or dropping to the dash)
 * cannot nudge the distance beside it sideways. It is pulled out of the
 * accessibility tree so TalkBack can never read the phantom digits.
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
        Box(contentAlignment = Alignment.CenterStart) {
            Text(
                text = SPEED_WIDTH_RESERVATION,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alpha(0f).clearAndSetSemantics {},
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The distance readout: glyph + number. */
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
            overflow = TextOverflow.Ellipsis,
        )
    }
}
