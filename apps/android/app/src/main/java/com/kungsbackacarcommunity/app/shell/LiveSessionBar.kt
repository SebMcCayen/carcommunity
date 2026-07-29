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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.drives.DriveFormatters
import kotlinx.coroutines.delay

/** Test tag on the live-session bar. */
const val LIVE_SESSION_BAR_TEST_TAG = "live_session_bar"

/**
 * A frosted bar shown at the top of the map — filling the strip between the
 * search icon and the profile avatar — WHILE a live-sharing session is running.
 * It reports two things and nothing else, because that is all that fits: how long
 * the session has run, and how far it has driven. It stretches to fill the whole
 * gap the search control and avatar frame (the same full-width treatment the
 * convoy bar got in #536).
 *
 * ## One left-aligned group, not two opposite ends
 * The two readouts used to be pushed to the bar's two ends
 * (`Arrangement.SpaceBetween`), which put a wide empty gap between a `0:07` and a
 * `1,2 km` — the bar read as two unrelated widgets rather than one status line.
 * They are now a single left-aligned group: a live dot, the running time, a
 * hairline separator, and the distance. Ragged space falls at the END of the bar,
 * where the eye expects it, and the group stays put as the numbers grow instead of
 * the gap breathing in and out once a second.
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
 * ## Where the two numbers come from
 * - Elapsed is `now − [sessionStartMillis]`, and which instant that start is
 *   is [LiveSessionElapsed]'s job — the host hands down an already-latched,
 *   device-clock anchor, so the first frame reads `0:00` and only ever counts up.
 *   The per-second ticker lives HERE, inside the bar, on purpose: reading a
 *   once-a-second state up in the large host composable would recompose the whole
 *   shell every second, so only this small bar re-reads the clock. The timer
 *   advances even while the car (and the GPS stream) is stationary, because it
 *   re-reads [now] rather than the last fix.
 * - [distanceMeters] is the drive recorder's running total for THIS session —
 *   the same value History would save — formatted with [DriveFormatters]. It is
 *   hoisted (it changes only on GPS fixes, which the host already observes).
 *
 * The host composes this only while a session is active; there is no "idle"
 * variant, so it never renders a zeroed-out placeholder.
 *
 * @param now the clock the ticker reads, injectable so the elapsed readout is
 *   deterministic in tests. Defaults to the wall clock.
 */
@Composable
fun LiveSessionBar(
    sessionStartMillis: Long,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
    now: () -> Long = { System.currentTimeMillis() },
) {
    // The once-a-second tick is scoped to THIS composable, so a running session
    // recomposes only the bar, not the whole shell. Re-keyed on the start moment
    // so a new session restarts the count from zero.
    val elapsedMillis by
        produceState(
            initialValue = LiveSessionElapsed.elapsedMillis(sessionStartMillis, now()),
            sessionStartMillis,
        ) {
            while (true) {
                value = LiveSessionElapsed.elapsedMillis(sessionStartMillis, now())
                delay(1000L)
            }
        }
    val elapsedText = LiveSessionFormat.elapsedLabel(elapsedMillis)
    val distanceText = DriveFormatters.formatDistance(distanceMeters)
    val description =
        stringResource(R.string.liveLocation_sessionBar, elapsedText, distanceText)
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
            DistanceMetric(
                value = distanceText,
                // Only the distance may shrink: at a narrow width the running clock
                // stays whole and this ellipsizes instead.
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
