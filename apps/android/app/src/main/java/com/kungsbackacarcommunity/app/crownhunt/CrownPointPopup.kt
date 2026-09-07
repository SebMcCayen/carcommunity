package com.kungsbackacarcommunity.app.crownhunt

import androidx.annotation.StringRes
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** Test tag on the whole admin-point popup, so UI tests can find it. */
const val CROWN_POINT_POPUP_TAG = "crown_point_popup"

/** Test tag on the Collect action. */
const val CROWN_POINT_COLLECT_TAG = "crown_point_collect"

/** Test tag on the Navigate action. */
const val CROWN_POINT_NAVIGATE_TAG = "crown_point_navigate"

/**
 * The panel opened by TAPPING a hand-placed admin Kronjakt point on the map.
 *
 * The sibling of [CrownSpawnPopup], for the OTHER crown source: these are the
 * curated, safety-approved reward points an admin created in the portal
 * (`crownHuntPoints`), not the auto-spawn tier. It answers what the marker
 * cannot — the point's name, what it pays, and what to do — and offers the one
 * action available: collect, decided entirely by the backend
 * (`crownHunt.submitClaim`) which owns the geofence, cooldown and daily-cap
 * checks and hands back a localized result.
 *
 * Two actions, stacked: **Collect**, gated by range so it is only live once the
 * member is within the point's geofence (the same in-range rule that lights the
 * marker on the map — see [CrownRange]); and **Navigate**, directly below it, to
 * start turn-by-turn to the point so a member who is too far can drive there. The
 * backend still owns the final claim decision (geofence, cooldown, daily cap);
 * the client gate only stops an obviously-doomed press and says why. Like the
 * spawn popup it never nags: it renders only while the host holds a tapped point,
 * and shows a driver a static message rather than anything that ticks.
 *
 * @param collectInRange whether the member is within the point's collect radius —
 *   the Collect button is grey/disabled until this is true, with a "get closer"
 *   hint, then enables. Computed by the host from the live location, exactly as
 *   the marker greying in [CrownRange] / [CrownPointMarkers] is.
 * @param onNavigate start navigation to the point's location (the host reuses the
 *   app's one navigate-to-a-point flow).
 */
@Composable
fun CrownPointPopup(
    point: CrownHuntPoint,
    status: CrownHuntClaimStatus,
    collectInRange: Boolean,
    onCollect: () -> Unit,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
    showLiveShareTip: Boolean = false,
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
                    .testTag(CROWN_POINT_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = KccSpacing.s5, vertical = KccSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                CrownPointHeader(point = point, onDismiss = onDismiss)
                // A finished call replaces the whole body: once the server has
                // answered there is nothing left to collect on this pass, so the
                // description and button give way to the outcome.
                val done = status as? CrownHuntClaimStatus.Done
                if (done != null) {
                    CrownPointOutcomeBody(result = done.outcome.result, points = done.outcome.pointsAwarded, allowance = done.outcome.allowance, onDismiss = onDismiss)
                } else {
                    CrownPointCollectBody(
                        point = point,
                        status = status,
                        collectInRange = collectInRange,
                        onCollect = onCollect,
                        onNavigate = onNavigate,
                    )
                    // Same live-share nudge as the auto-spawn popup: only while the
                    // point is still collectable and only when the caller confirms
                    // the rule is on AND the member is not currently live-sharing.
                    if (showLiveShareTip) {
                        CrownLiveShareTip()
                    }
                }
            }
        }
    }
}

/** The admin-point disc + royal silhouette, its name and its value. */
@Composable
private fun CrownPointHeader(point: CrownHuntPoint, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        // The same disc + silhouette the marker is drawn from, so the thing in
        // the popup is recognisably the thing that was tapped.
        Box(
            modifier =
                Modifier
                    .size(KccSpacing.s8)
                    .clip(CircleShape)
                    .background(Color(CrownMarkerStyle.ADMIN_POINT_DISC)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(crownPointGlyphRes()),
                contentDescription = null,
                tint = Color(CrownMarkerStyle.adminPointGlyphColorArgb()),
                modifier = Modifier.size(KccSpacing.s5),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = point.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // crownHunt_rewardLabel carries a {points} placeholder
                // ("Reward: {points} KP") — substitute it, don't append.
                text =
                    stringResource(R.string.crownHunt_rewardLabel)
                        .replace("{points}", point.rewardPoints.toString()),
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

/** Description, the collect hint / range gate, any status line, then Collect + Navigate. */
@Composable
private fun CrownPointCollectBody(
    point: CrownHuntPoint,
    status: CrownHuntClaimStatus,
    collectInRange: Boolean,
    onCollect: () -> Unit,
    onNavigate: () -> Unit,
) {
    point.description?.takeIf { it.isNotBlank() }?.let { description ->
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Text(
        // In range: "collect it here". Out of range: "get closer" — the same
        // in-range rule that lit (or greyed) the marker on the map, so the popup
        // never contradicts what the member just tapped.
        text =
            stringResource(
                if (collectInRange) {
                    R.string.crownHunt_pointCollectHint
                } else {
                    R.string.crownHunt_pointCollectRangeHint
                },
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (status == CrownHuntClaimStatus.NeedsLocation) {
        Text(
            text = stringResource(R.string.crownHunt_locationUnavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (status == CrownHuntClaimStatus.Failed) {
        Text(
            text = stringResource(R.string.crownHunt_errorClaim),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    val collecting = status == CrownHuntClaimStatus.Claiming
    Button(
        onClick = onCollect,
        modifier = Modifier.fillMaxWidth().testTag(CROWN_POINT_COLLECT_TAG),
        // Grey until the member is within the geofence AND no claim is in flight.
        // The backend re-checks the geofence, so the gate only spares an
        // obviously-doomed round-trip and makes "why can't I collect?" answerable
        // before it is asked.
        enabled = collectInRange && !collecting,
    ) {
        Text(
            text =
                stringResource(
                    if (collecting) R.string.crownHunt_claiming else R.string.crownHunt_collectButton,
                ),
        )
    }
    // Directly BELOW Collect: drive there. Offered whether or not in range — a
    // member who is too far needs exactly this to close the gap; one who is
    // already here simply will not use it.
    OutlinedButton(
        onClick = onNavigate,
        modifier = Modifier.fillMaxWidth().testTag(CROWN_POINT_NAVIGATE_TAG),
    ) {
        Text(text = stringResource(R.string.crownHunt_navigate))
    }
}

/** What the server said. One line, one way out. */
@Composable
private fun CrownPointOutcomeBody(
    result: CrownHuntClaimResult,
    points: Int?,
    allowance: CrownAllowance?,
    onDismiss: () -> Unit,
) {
    val awarded = result == CrownHuntClaimResult.AWARDED
    CrownAllowanceText(allowance)
    Text(
        text = stringResource(if (result == CrownHuntClaimResult.DAILY_LIMIT_REACHED && allowance != null) R.string.crownHunt_allowanceReached else crownHuntClaimResultRes(result)),
        style =
            if (awarded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (awarded) {
        // The payoff, from the server's answer — that is what actually landed in
        // the balance, so it is honest even if the document's value drifted.
        Text(
            text = stringResource(R.string.crownHunt_spawnResultAwardedPoints, points ?: 0),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.crownHunt_spawnClose))
    }
}

/**
 * The localized message for a submitClaim result. Public + pure so the popup and
 * the Kronjakt hub screen share ONE mapping — a new result code cannot be
 * localized in one place and forgotten in the other.
 */
@StringRes
fun crownHuntClaimResultRes(result: CrownHuntClaimResult): Int =
    when (result) {
        CrownHuntClaimResult.AWARDED -> R.string.crownHunt_resultAwarded
        CrownHuntClaimResult.ALREADY_CLAIMED -> R.string.crownHunt_resultAlreadyClaimed
        CrownHuntClaimResult.OUTSIDE_GEOFENCE -> R.string.crownHunt_resultOutsideGeofence
        CrownHuntClaimResult.MOVING_TOO_FAST -> R.string.crownHunt_resultMovingTooFast
        CrownHuntClaimResult.POSITION_TOO_OLD -> R.string.crownHunt_resultPositionTooOld
        CrownHuntClaimResult.POINT_INACTIVE -> R.string.crownHunt_resultPointInactive
        CrownHuntClaimResult.COOLDOWN_ACTIVE -> R.string.crownHunt_resultCooldownActive
        CrownHuntClaimResult.DAILY_LIMIT_REACHED -> R.string.crownHunt_resultDailyLimit
        CrownHuntClaimResult.RISK_REVIEW -> R.string.crownHunt_resultRiskReview
        CrownHuntClaimResult.FEATURE_DISABLED -> R.string.crownHunt_resultFeatureDisabled
        CrownHuntClaimResult.NOT_ELIGIBLE -> R.string.crownHunt_resultNotEligible
    }
