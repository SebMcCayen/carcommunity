package com.kungsbackacarcommunity.app.police

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Test tag on the "still there?" confirm action (non-reporter). */
const val POLICE_DETAILS_CONFIRM_TAG = "police_details_confirm"

/** Test tag on the "Borta/Not here" dispute action (non-reporter). */
const val POLICE_DETAILS_DISPUTE_TAG = "police_details_dispute"

/** Test tag on the "remove my pin" action (reporter only). */
const val POLICE_DETAILS_REMOVE_TAG = "police_details_remove"

/** Test tag on the "confirmed by N" line. */
const val POLICE_DETAILS_CONFIRM_COUNT_TAG = "police_details_confirm_count"

/** Test tag on the "reported gone by N" line. */
const val POLICE_DETAILS_DISPUTE_COUNT_TAG = "police_details_dispute_count"

/**
 * The sheet opened by TAPPING a police pin on the map — the police-layer sibling
 * of [com.kungsbackacarcommunity.app.incidents.IncidentDetailsSheet], modelled on
 * the same tap→dialog interaction but leaner (a police pin has no category, note,
 * or age filter — it is a transient "police here" marker).
 *
 * It surfaces the shared verify signal and offers the one action appropriate to
 * who is looking:
 *  - the REPORTER ([PoliceReport.mine] == true) is offered **Ta bort** only
 *    (`police.remove`). They are never asked to verify their own pin.
 *  - EVERYONE ELSE is offered the "Är den kvar?" pair: **Ja, den är kvar**
 *    confirms it (`police.confirm`) and **Nej, den är borta** disputes it
 *    (`police.dispute`). Both are recorded once per member and shown as counts;
 *    a dispute INFORMS the next driver, it does not take the pin off the map (the
 *    pin ages out on its own short TTL, or its reporter removes it).
 *
 * Both verify counts are shown whenever either is non-zero — never a single netted
 * verdict — so a driver approaching the spot weighs "3 confirm / 1 says gone"
 * itself, exactly as the incident sheet shows both tallies.
 *
 * Purely renders the decision from [PoliceReport.mine] + the counts; the callables
 * are wired by the host.
 */
@Composable
fun PoliceDetailsSheet(
    pin: PoliceReport,
    onConfirm: () -> Unit,
    onDispute: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    // One-call-per-press guards: the sheet outlives the round-trip (it closes when
    // the pin leaves the map or on Close), so without these a second tap during the
    // call would fire a duplicate.
    confirmInProgress: Boolean = false,
    disputeInProgress: Boolean = false,
    removeInProgress: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                PoliceBadge()
                Text(
                    text = stringResource(R.string.police_sheetTitle),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Text(
                    text = stringResource(R.string.police_reportedByMember),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // BOTH tallies, always, whenever either is non-zero — never one
                // netted number. The reader is about to drive into the spot.
                if (pin.confirmationCount > 0) {
                    Text(
                        text = stringResource(R.string.police_confirmedBy, pin.confirmationCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(POLICE_DETAILS_CONFIRM_COUNT_TAG),
                    )
                }
                if (pin.disputeCount > 0) {
                    Text(
                        text = stringResource(R.string.police_disputedBy, pin.disputeCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(POLICE_DETAILS_DISPUTE_COUNT_TAG),
                    )
                }
                if (pin.mine) {
                    Text(
                        text = stringResource(R.string.police_ownPinHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.police_stillHereQuestion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (pin.mine) {
                TextButton(
                    onClick = onRemove,
                    enabled = !removeInProgress,
                    modifier = Modifier.testTag(POLICE_DETAILS_REMOVE_TAG),
                ) {
                    Text(stringResource(R.string.police_removeAction))
                }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = !confirmInProgress,
                    modifier = Modifier.testTag(POLICE_DETAILS_CONFIRM_TAG),
                ) {
                    Text(stringResource(R.string.police_stillHereYes))
                }
            }
        },
        dismissButton = {
            // "Nej, den är borta" sits beside Close rather than in the primary slot
            // (the same restraint as the incident sheet's clear vote), so the
            // informational-but-negative action is not the reflex tap.
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                if (!pin.mine) {
                    TextButton(
                        onClick = onDispute,
                        enabled = !disputeInProgress,
                        modifier = Modifier.testTag(POLICE_DETAILS_DISPUTE_TAG),
                    ) {
                        Text(stringResource(R.string.police_stillHereNo))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.incidents_close))
                }
            }
        },
    )
}

/**
 * The dialog-sized police badge: the police glyph on the SAME blue disc the map
 * marker draws ([PoliceMapMarkers.DISC_COLOR_ARGB]), so the pin you tapped and the
 * thing you are now reading about are visibly the same object.
 */
@Composable
private fun PoliceBadge() {
    Box(
        modifier =
            Modifier
                .size(BADGE_SIZE)
                .clip(CircleShape)
                .background(Color(PoliceMapMarkers.DISC_COLOR_ARGB)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_incident_police),
            // The title text already says "Polis"; the badge is decorative.
            contentDescription = null,
            tint = Color(PoliceMapMarkers.GLYPH_COLOR_ARGB),
            modifier = Modifier.size(BADGE_GLYPH_SIZE),
        )
    }
}

private val BADGE_SIZE = KccSpacing.s8
private val BADGE_GLYPH_SIZE = BADGE_SIZE * 0.6f
