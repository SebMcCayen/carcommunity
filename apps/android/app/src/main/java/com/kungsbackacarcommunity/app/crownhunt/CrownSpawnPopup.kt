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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Test tag on the whole crown popup, so UI tests can find it. */
const val CROWN_SPAWN_POPUP_TAG = "crown_spawn_popup"

/** Test tag on the Collect action. */
const val CROWN_SPAWN_COLLECT_TAG = "crown_spawn_collect"

/** Test tag on the Navigate action. */
const val CROWN_SPAWN_NAVIGATE_TAG = "crown_spawn_navigate"

/**
 * The panel opened by TAPPING a Kronjakt crown on the map.
 *
 * Answers the three things the marker itself cannot — which tier it is, what it
 * pays, and how far away it is — and then offers the single action that can be
 * offered, honestly gated.
 *
 * ## What this deliberately does not do
 *
 * There is **no speed anywhere**: no readout, no "you are doing X, get under Y",
 * no gauge. Speed exists in this feature solely as a safety gate inside
 * [CrownCollectGate], and showing the number would invite a driver to watch it
 * and shave it, which is precisely the behaviour the gate exists to prevent.
 *
 * There is **no timer and no countdown**. Crowns do expire (6–48 h depending on
 * tier), but nothing here ticks: a member who has to drive another ten minutes
 * to somewhere safe to stop must not feel they are racing the popup.
 *
 * And it **never nags**. This composable renders only while the host holds a
 * tapped crown; it schedules nothing, plays nothing and flashes nothing. A
 * driver who taps a crown at 60 km/h sees a static "stop the car first" and then
 * silence.
 *
 * ## Why the button is disabled rather than absent
 *
 * A refusal the user cannot see coming is worse than one they can. The button
 * stays visible and disabled with the reason written above it, so "why can't I
 * collect this?" is already answered before it is asked — and the reason is the
 * REAL one ([CrownSpawnMessages.refusalTitleRes] is exhaustive over the states,
 * so "too far" and "stop first" can never be collapsed into one vague line).
 */
@Composable
fun CrownSpawnPopup(
    spawn: CrownSpawn,
    state: CrownCollectState,
    status: CrownClaimStatus,
    distanceMeters: Double?,
    onCollect: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                    .testTag(CROWN_SPAWN_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = KccSpacing.s5, vertical = KccSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                CrownHeader(spawn = spawn, onDismiss = onDismiss)
                // A finished call replaces the whole body: once the server has
                // answered, the distance and the gate are history — showing them
                // beside "someone got there first" would invite a second tap on
                // a crown that no longer exists.
                val done = status as? CrownClaimStatus.Done
                if (done != null) {
                    CrownOutcomeBody(outcome = done.outcome, onDismiss = onDismiss)
                } else {
                    CrownCollectBody(
                        state = state,
                        status = status,
                        distanceMeters = distanceMeters,
                        collectRadiusMeters = spawn.collectRadiusMeters,
                        onCollect = onCollect,
                        onNavigate = onNavigate,
                    )
                }
            }
        }
    }
}

/** The tier badge, its name and its value — the crown as the map drew it. */
@Composable
private fun CrownHeader(spawn: CrownSpawn, onDismiss: () -> Unit) {
    val rarityLabel = stringResource(CrownSpawnMessages.rarityLabelRes(spawn.rarity))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        // The same disc + silhouette the marker is drawn from, so the thing in
        // the popup is recognisably the thing that was tapped. Built from the
        // same CrownMarkerStyle the bitmap uses, not a second palette.
        Box(
            modifier =
                Modifier
                    .size(KccSpacing.s8)
                    .clip(CircleShape)
                    .background(Color(CrownMarkerStyle.discColorArgb(spawn.rarity))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(crownGlyphRes(spawn.rarity)),
                // The rarity is announced as text right beside this; a second
                // announcement would just be noise.
                contentDescription = null,
                tint = Color(CrownMarkerStyle.glyphColorArgb(spawn.rarity)),
                modifier = Modifier.size(KccSpacing.s5),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rarityLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // The crown's OWN reward, as the server stamped it — not the
                // tier's table value, so a server-side retune shows up here
                // without an app release.
                text = stringResource(R.string.crownHunt_spawnReward, spawn.rewardPoints),
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
}

/** Distance, the reason the button is not live (if it is not), and the button. */
@Composable
private fun CrownCollectBody(
    state: CrownCollectState,
    status: CrownClaimStatus,
    distanceMeters: Double?,
    collectRadiusMeters: Double,
    onCollect: () -> Unit,
    onNavigate: () -> Unit,
) {
    if (distanceMeters != null && distanceMeters.isFinite()) {
        Text(
            text = crownDistanceText(distanceMeters),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    CrownSpawnMessages.refusalTitleRes(state)?.let { titleRes ->
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    CrownSpawnMessages.refusalDetailRes(state)?.let { detailRes ->
        Text(
            text =
                if (detailRes == R.string.crownHunt_spawnMoveCloserDetail) {
                    // The only refusal detail that takes an argument: how close
                    // you actually have to get. The crown's OWN radius, read off
                    // the document, so a server-side retune is honest here too.
                    stringResource(detailRes, CrownDistanceFormat.wholeMetres(collectRadiusMeters))
                } else {
                    stringResource(detailRes)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // "Wait a moment" — the app has no second fix yet, so it cannot ASK, let
    // alone be refused. Distinct from a refusal on purpose: nothing has gone
    // wrong and the member need do nothing but stay put.
    //
    // Suppressed when the gate is ALREADY saying this (no fix at all), so the
    // same sentence is never printed twice under itself.
    if (status == CrownClaimStatus.NeedsPosition && state != CrownCollectState.NoPosition) {
        Text(
            text = stringResource(R.string.crownHunt_spawnNoPositionDetail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (status == CrownClaimStatus.Failed) {
        Text(
            text = stringResource(R.string.crownHunt_spawnErrorClaim),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    val collecting = status == CrownClaimStatus.Collecting
    // The confirming state is the honest "hold on a moment" that replaces a button
    // that looked live and then refused with NeedsPosition. It carries an optional
    // seconds hint; when present the button counts it down, otherwise it just says
    // it is confirming. The label choice for every OTHER case lives in
    // [CrownSpawnMessages.collectActionLabelRes], so it is unit-tested.
    val confirmingSeconds =
        (state as? CrownCollectState.Confirming)?.secondsRemaining?.takeIf { it > 0 }
    val buttonText =
        if (!collecting && confirmingSeconds != null) {
            stringResource(R.string.crownHunt_spawnConfirmingSeconds, confirmingSeconds)
        } else {
            stringResource(CrownSpawnMessages.collectActionLabelRes(state, collecting))
        }
    Button(
        onClick = onCollect,
        modifier = Modifier.fillMaxWidth().testTag(CROWN_SPAWN_COLLECT_TAG),
        // The gate is the single source of truth for enablement; the in-flight
        // guard is the only thing added here, so one press is one call.
        enabled = CrownCollectGate.isCollectEnabled(state) && !collecting,
    ) {
        Text(text = buttonText)
    }
    // Directly BELOW Collect: drive to the crown. Offered whatever the gate says —
    // a member who is too far or moving needs exactly this to get parked beside
    // it; one already in range simply will not use it.
    OutlinedButton(
        onClick = onNavigate,
        modifier = Modifier.fillMaxWidth().testTag(CROWN_SPAWN_NAVIGATE_TAG),
    ) {
        Text(text = stringResource(R.string.crownHunt_navigate))
    }
}

/** What the server said. One line, one way out. */
@Composable
private fun CrownOutcomeBody(outcome: CrownSpawnClaimOutcome, onDismiss: () -> Unit) {
    val awarded = outcome.result == CrownSpawnClaimResult.AWARDED
    Text(
        text = stringResource(CrownSpawnMessages.resultMessageRes(outcome.result)),
        style =
            if (awarded) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (awarded) {
        // The payoff, big and on its own line. `pointsAwarded` comes from the
        // server's answer rather than the crown document, because THAT is what
        // actually landed in the balance — showing the document's value would be
        // a guess that could disagree with the ledger.
        val points = outcome.pointsAwarded ?: 0
        Text(
            text = stringResource(R.string.crownHunt_spawnResultAwardedPoints, points),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    TextButton(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.crownHunt_spawnClose))
    }
}

/**
 * "120 m away" / "1.4 km away", per [CrownDistanceFormat].
 *
 * The kilometre form is locale-formatted so a Swedish device shows "1,4 km"
 * rather than "1.4 km" — the number is read in Swedish everywhere else in the
 * app, and one stray decimal point is exactly the kind of detail that makes a
 * screen feel translated rather than written.
 *
 * Read through [LocalLocale] rather than `Locale.getDefault()`, matching
 * `ConvoyMapAwarenessOverlay`, which formats the same "%.1f km": the composition
 * local is observable, so a locale change recomposes this instead of leaving a
 * popup that is open across the change formatting to the old one.
 */
@Composable
private fun crownDistanceText(meters: Double): String =
    if (CrownDistanceFormat.useKilometres(meters)) {
        stringResource(
            R.string.crownHunt_spawnDistanceKm,
            String.format(
                LocalLocale.current.platformLocale,
                "%.1f",
                CrownDistanceFormat.kilometres(meters),
            ),
        )
    } else {
        stringResource(R.string.crownHunt_spawnDistance, CrownDistanceFormat.wholeMetres(meters))
    }
