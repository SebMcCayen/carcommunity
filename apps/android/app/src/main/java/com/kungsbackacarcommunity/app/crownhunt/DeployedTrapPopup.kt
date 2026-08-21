package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import kotlinx.coroutines.delay

/** Test tag on the whole deployed-trap detail popup, so UI tests can find it. */
const val DEPLOYED_TRAP_POPUP_TAG = "deployed_trap_popup"

/** Test tag on the live remaining-time line, so UI tests can assert the countdown. */
const val DEPLOYED_TRAP_REMAINING_TAG = "deployed_trap_remaining"

/** Bear-trap purple, matched to the map glyph so the popup reads as the tapped thing. */
private val TRAP_PURPLE = Color(0xFF9B5CFF)

/**
 * The panel opened by TAPPING the placer's OWN deployed Spikmatta on the map.
 *
 * The map glyph shows only "a trap is here, and roughly how much life is left" (the
 * depleting bar); this popup answers the three things it cannot — WHAT the perk is
 * ("Spikmatta" / "Spike strip"), WHAT it does (drains KP from rivals who drive into
 * it), and EXACTLY how long it has left, as a live "2 min 30 s kvar" countdown that
 * ticks down while the popup is open.
 *
 * A Spikmatta is placer-only by construction (firestore.rules scopes the read to
 * the member who placed it), so this is inherently owner-only — it only ever opens
 * for the caller's own trap.
 *
 * Names/labels come from the localized string resources (the contracts localization
 * mirror, the offline-authoritative source the shop's [PerkNames] also prefers for
 * the known perks) rather than the Swedish-only catalog blurb, so an English device
 * reads it in English.
 */
@Composable
fun DeployedTrapPopup(
    trap: OwnTrapMarker,
    onDismiss: () -> Unit,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    // A self-terminating 1 s ticker for the countdown. Runs only while the popup is
    // composed (the host holds the tapped trap), and when the trap EXPIRES it actively
    // DISMISSES the popup rather than only stopping the ticker: `observeOwnActiveTraps`
    // uses a fixed `expiresAt > now − margin` query plus a local moving-time filter, so
    // no fresh Firestore snapshot is guaranteed just because time passed — a bare break
    // would leave this popup lingering over an expired trap. rememberUpdatedState keeps
    // the dismiss current across recompositions even though the effect is keyed on the
    // (stable) trap id.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var now by remember(trap.trapId) { mutableLongStateOf(nowProvider()) }
    LaunchedEffect(trap.trapId) {
        while (true) {
            now = nowProvider()
            if (trap.expiresAtMillis <= now) {
                currentOnDismiss()
                break
            }
            delay(1_000L)
        }
    }
    val clock = PerkMapVisuals.remainingClock(trap.expiresAtMillis, now)

    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(KccSpacing.s4)
                    .fillMaxWidth()
                    .testTag(DEPLOYED_TRAP_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = KccSpacing.s5, vertical = KccSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                // Header: the purple trap glyph + its name and family, so the panel is
                // recognisably the marker that was tapped.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(KccSpacing.s8)
                                .clip(CircleShape)
                                .background(TRAP_PURPLE.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Dangerous,
                            // The name is announced as text right beside this.
                            contentDescription = null,
                            tint = TRAP_PURPLE,
                            modifier = Modifier.size(KccSpacing.s5),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text =
                                perkDisplayName(
                                    perkId = "spike_strip",
                                    nameSv = stringResource(R.string.crownHunt_perkNameSpikeStrip),
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.crownHunt_perkKindTrap),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.crownHunt_spawnClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // What it does.
                Text(
                    text = stringResource(R.string.crownHunt_perkTrapEffect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Live remaining time — WITH numbers here (unlike the map bar).
                Text(
                    text = remainingClockText(clock),
                    modifier = Modifier.testTag(DEPLOYED_TRAP_REMAINING_TAG),
                    style = MaterialTheme.typography.titleSmall,
                    color =
                        if (clock.isExpired) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            TRAP_PURPLE
                        },
                )
            }
        }
    }
}

/**
 * The remaining-time line: "2 h 5 min kvar" while an hour or more is left, "2 min
 * 30 s kvar" under an hour, "45 s kvar" in the final minute, and an expired notice
 * at zero. The level chosen from [PerkMapVisuals.remainingClock] (pure), so only the
 * string selection lives here.
 */
@Composable
private fun remainingClockText(clock: PerkMapVisuals.RemainingClock): String =
    when {
        clock.isExpired -> stringResource(R.string.crownHunt_perkDetailExpired)
        clock.hours > 0 ->
            stringResource(R.string.crownHunt_perkDetailRemainingHms, clock.hours, clock.minutes)
        clock.minutes > 0 ->
            stringResource(R.string.crownHunt_perkDetailRemainingMs, clock.minutes, clock.seconds)
        else -> stringResource(R.string.crownHunt_perkDetailRemainingS, clock.seconds)
    }
