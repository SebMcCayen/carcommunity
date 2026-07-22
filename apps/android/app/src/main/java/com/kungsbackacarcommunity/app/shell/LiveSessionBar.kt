package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
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
 * A compact, frosted pill shown at the top of the map — in the strip between the
 * search icon and the profile avatar — WHILE a live-sharing session is running.
 * It reports two things and nothing else, because that is all that fits: how long
 * the session has run, and how far it has driven.
 *
 * ## Icons + numbers, never labels
 * The bar has to sit beside the search control and the avatar and never crowd
 * either, so it carries a clock glyph + a running `M:SS` / `Hh MMm` time and a
 * distance glyph + a `km`/`m` figure — no "elapsed" or "distance" words that
 * would overflow the strip (the same reason the convoy bar shows a bare count).
 * The full sentence is kept as the pill's [contentDescription] for TalkBack.
 *
 * ## Where the two numbers come from
 * - Elapsed is derived from [sessionStartMillis] (the drive recorder's start
 *   moment, or the session's expiry−duration). The per-second ticker lives HERE,
 *   inside the bar, on purpose: reading a once-a-second state up in the large host
 *   composable would recompose the whole shell every second, so only this small
 *   bar re-reads the clock. The timer advances even while the car (and the GPS
 *   stream) is stationary, because it re-reads [now] rather than the last fix.
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
            initialValue = (now() - sessionStartMillis).coerceAtLeast(0L),
            sessionStartMillis,
        ) {
            while (true) {
                value = (now() - sessionStartMillis).coerceAtLeast(0L)
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
                .testTag(LIVE_SESSION_BAR_TEST_TAG)
                .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(KccRadius.full),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier.padding(horizontal = KccSpacing.s3, vertical = KccSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            LiveSessionMetric(
                icon = { modifierIcon ->
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = modifierIcon,
                    )
                },
                value = elapsedText,
            )
            LiveSessionMetric(
                icon = { modifierIcon ->
                    Icon(
                        imageVector = Icons.Filled.Straighten,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = modifierIcon,
                    )
                },
                value = distanceText,
            )
        }
    }
}

/** One icon + number pair inside the live-session bar. */
@Composable
private fun LiveSessionMetric(
    icon: @Composable (Modifier) -> Unit,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        icon(Modifier.size(18.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
