package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Test tags for the deploy menu (used by instrumented UI tests). */
const val PERK_DEPLOY_POPUP_TAG = "perk_deploy_popup"

/** Per-perk activate button, suffixed with the perkId. */
fun perkDeployActivateTag(perkId: String): String = "perk_deploy_activate_$perkId"

/**
 * The Kronjakt perk DEPLOY menu — a transparent [Popup] over the map listing the
 * member's three perks with BIG icons, the owned count, the live active-state
 * (a countdown for shield/boost, "1 active trap" for a placed trap), and a
 * one-tap ACTIVATE action per perk.
 *
 * One-tap by design: the menu-open plus the tap is deliberate enough that no
 * extra confirm modal is needed (the perks are usable while driving, so a second
 * dialog would be a hazard, not a safeguard). The tap gives immediate feedback
 * via [status] — a spinner while the deploy is in flight, then a success line or
 * a friendly error. The ACTIVATE button is disabled while the member owns none,
 * while the effect is already active (re-raising would waste a unit), and while
 * any deploy is in flight — all decided by the pure [PerkDeploy] logic, so the
 * enablement is unit-tested rather than asserted only on a device.
 *
 * PURE presentation: it takes the derived [menuState] + [status] + callbacks; it
 * holds no Firebase and no coordinator, so the host (AuthenticatedApp) owns the
 * wiring exactly as it does for the chat hub and live-share popups.
 */
@Composable
fun PerkDeployPopup(
    menuState: PerkDeployMenuState,
    status: PerkDeployStatus,
    nowMillis: Long,
    onDeploy: (PerkDeployItem) -> Unit,
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
                    .padding(16.dp)
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .testTag(PERK_DEPLOY_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            val maxPopupHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
            Column(
                modifier =
                    Modifier
                        .heightIn(max = maxPopupHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(KccSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                Text(
                    text = stringResource(R.string.crownHunt_deployTitle),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                when (menuState) {
                    PerkDeployMenuState.Loading ->
                        Text(
                            text = stringResource(R.string.crownHunt_deployLoading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    PerkDeployMenuState.Error ->
                        Text(
                            text = stringResource(R.string.crownHunt_deployError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                    is PerkDeployMenuState.Loaded ->
                        // Empty = owns none of any perk AND nothing active (the
                        // catalog-built rows are never literally empty), so the
                        // first-run user gets guidance, not a wall of disabled rows.
                        if (menuState.isEmpty) {
                            Text(
                                text = stringResource(R.string.crownHunt_deployEmpty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            for (item in menuState.items) {
                                PerkDeployRow(
                                    item = item,
                                    status = status,
                                    nowMillis = nowMillis,
                                    onDeploy = onDeploy,
                                )
                            }
                        }
                }
                DeployStatusBanner(status = status, menuState = menuState)
            }
        }
    }
}

/**
 * One perk row: a BIG kind icon, the name + owned count, the active-state line,
 * and the ACTIVATE button (a spinner while ITS deploy is in flight, disabled for
 * every row while ANY deploy is in flight or when [PerkDeployItem.activatable] is
 * false).
 */
@Composable
private fun PerkDeployRow(
    item: PerkDeployItem,
    status: PerkDeployStatus,
    nowMillis: Long,
    onDeploy: (PerkDeployItem) -> Unit,
) {
    val deployingThis =
        status is PerkDeployStatus.Deploying && status.perkId == item.perkId
    val anyDeploying = status is PerkDeployStatus.Deploying
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // BIG kind icon in a tinted disc.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = perkKindIcon(item.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Text(
                text = perkDisplayName(item.perkId, item.name, item.nameEn),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.crownHunt_deployOwnedLabel,
                    item.ownedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActiveStateLine(item = item, nowMillis = nowMillis)
        }
        Button(
            onClick = { onDeploy(item) },
            enabled = item.activatable && !anyDeploying,
            modifier = Modifier.testTag(perkDeployActivateTag(item.perkId)),
        ) {
            if (deployingThis) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.crownHunt_deployActivateButton))
            }
        }
    }
}

/**
 * The active-state line under a perk: a live once-per-second countdown for a
 * shield/boost that is still running ("Aktiv – 2 min 30 s kvar"), a "1 active
 * trap" note for a placed trap, or nothing when the effect is idle/expired.
 *
 * Re-reads the pure [PerkDeploy.remaining] against the ticking [nowMillis] so the
 * line drops away the instant the window elapses, even before the menu-state flow
 * next recomputes `active` — no stale "0 s" is ever shown.
 */
@Composable
private fun ActiveStateLine(item: PerkDeployItem, nowMillis: Long) {
    when {
        item.kind == PerkKind.TRAP && item.activeTrapCount > 0 ->
            Text(
                text = stringResource(
                    R.string.crownHunt_deployActiveTraps,
                    item.activeTrapCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

        item.active && item.activeUntilMillis != null -> {
            val remaining = PerkDeploy.remaining(item.activeUntilMillis, nowMillis)
            if (remaining !is PerkRemaining.Expired) {
                Text(
                    text = stringResource(
                        R.string.crownHunt_deployActiveFor,
                        formatRemaining(remaining),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The result banner for the most recent deploy (success or friendly error). */
@Composable
private fun DeployStatusBanner(
    status: PerkDeployStatus,
    menuState: PerkDeployMenuState,
) {
    when (status) {
        PerkDeployStatus.Idle, is PerkDeployStatus.Deploying -> Unit

        is PerkDeployStatus.Deployed -> {
            val deployedItem =
                (menuState as? PerkDeployMenuState.Loaded)
                    ?.items
                    ?.firstOrNull { it.perkId == status.perkId }
            val perkName =
                deployedItem?.let { perkDisplayName(it.perkId, it.name, it.nameEn) } ?: status.perkId
            val message =
                if (status.alreadyDeployed) {
                    stringResource(R.string.crownHunt_deployAlreadyMessage)
                } else {
                    stringResource(
                        deploySuccessMessageRes(status.kind),
                        perkName,
                    )
                }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        is PerkDeployStatus.Failed ->
            Text(
                text = stringResource(deployFailureMessageRes(status.reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** Big Material glyph per perk kind (consistent with the map control styling). */
private fun perkKindIcon(kind: PerkKind): ImageVector =
    when (kind) {
        PerkKind.TRAP -> Icons.Filled.Dangerous
        PerkKind.SHIELD -> Icons.Filled.Shield
        PerkKind.BOOST -> Icons.Filled.Bolt
    }

/** Resource id for a deploy-success message, keyed on the effect kind. */
private fun deploySuccessMessageRes(kind: PerkKind): Int =
    when (kind) {
        PerkKind.TRAP -> R.string.crownHunt_deploySuccessTrap
        PerkKind.SHIELD -> R.string.crownHunt_deploySuccessShield
        PerkKind.BOOST -> R.string.crownHunt_deploySuccessBoost
    }

/** Resource id for a deploy-failure reason's message (localized sv/en). */
private fun deployFailureMessageRes(reason: PerkDeployFailureReason): Int =
    when (reason) {
        PerkDeployFailureReason.NO_LOCATION -> R.string.crownHunt_deployErrorNoLocation
        PerkDeployFailureReason.ACTIVATION_LIMIT -> R.string.crownHunt_deployErrorActivationLimit
        PerkDeployFailureReason.UNAVAILABLE -> R.string.crownHunt_deployErrorUnavailable
        PerkDeployFailureReason.UNKNOWN -> R.string.crownHunt_deployErrorUnknown
    }

/**
 * Localizes one [PerkRemaining] bucket into "Xm Ys" / "Y s". Purely
 * presentational (the pure countdown maths is [PerkDeploy.remaining]); a screen
 * reader hears the same string. [PerkRemaining.Expired] never reaches here — the
 * caller omits the whole line for an expired window.
 */
@Composable
private fun formatRemaining(remaining: PerkRemaining): String =
    when (remaining) {
        is PerkRemaining.MinutesSeconds ->
            stringResource(
                R.string.crownHunt_deployRemainingMs,
                remaining.minutes,
                remaining.seconds,
            )

        is PerkRemaining.SecondsOnly ->
            stringResource(R.string.crownHunt_deployRemainingS, remaining.seconds)

        PerkRemaining.Expired -> ""
    }
