package com.kungsbackacarcommunity.app.incidents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** Test tag on the "still there?" confirmation action. */
const val INCIDENT_DETAILS_CONFIRM_TAG = "incident_details_confirm"

/** Test tag on the "remove my report" action. */
const val INCIDENT_DETAILS_REMOVE_TAG = "incident_details_remove"

/** Test tag on the "confirmed by N" line shown for a confirmable incident. */
const val INCIDENT_DETAILS_CONFIRM_COUNT_TAG = "incident_details_confirm_count"

/**
 * The sheet opened by TAPPING an incident marker on the map.
 *
 * Answers the three things the badge itself cannot: what category it is, how
 * long ago it was reported, and where it came from (a member, or the
 * Trafikverket import) — then offers the ONE action that makes sense for this
 * viewer, per [IncidentDetails.actionFor]:
 *
 *  - someone else's member report → "Still there?", which is what Seb asked for.
 *    It is wired to `incidents-confirm` (see [ConfirmAvailability]): tapping it
 *    corroborates the incident, extends its life, and bumps the shared
 *    confirmation count. When others have already confirmed, a "confirmed by N"
 *    line shows above the button as social proof. The button disables while a
 *    confirmation is in flight ([confirmInProgress]) so a double-tap cannot fire
 *    two calls.
 *  - your own member report → the existing remove action, which IS wired
 *    (`incidents-remove`). You are never offered "still there?" on your own
 *    report.
 *  - an imported Trafikverket row → neither, with a line saying why. The backend
 *    rejects removing imported incidents for everyone, admins included.
 *
 * All the branching logic is in [IncidentDetails] (pure, unit-tested); this
 * composable only renders the decision.
 */
@Composable
fun IncidentDetailsSheet(
    incident: Incident,
    viewerUid: String?,
    nowMillis: Long,
    onConfirm: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    // True while a removal is in flight. The sheet now stays open across the
    // round-trip (it closes when the incident leaves the map, not when the
    // button is pressed), which would otherwise leave the button live long
    // enough to fire a second delete for the same incident.
    removeInProgress: Boolean = false,
    // True while a confirmation is in flight — same one-call-per-press guard as
    // removeInProgress, so a double-tap cannot fire two `incidents-confirm` calls.
    confirmInProgress: Boolean = false,
) {
    val action = IncidentDetails.actionFor(incident, viewerUid)
    val confirmWired = IncidentDetails.confirmAvailability == ConfirmAvailability.Wired
    val label = incidentTypeLabel(incident.type)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                IncidentBadge(type = incident.type)
                Text(text = label, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                Text(
                    text = incidentAgeLabel(IncidentDetails.ageOf(incident, nowMillis)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text =
                        stringResource(
                            when (IncidentDetails.originOf(incident)) {
                                IncidentOrigin.Member -> R.string.incidents_sourceMember
                                IncidentOrigin.Trafikverket -> R.string.incidents_sourceImported
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The reporter's own note, when they left one.
                incident.note?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                    Text(text = note, style = MaterialTheme.typography.bodyMedium)
                }
                when (action) {
                    IncidentAction.Confirm ->
                        // "Confirmed by N" as ambient social proof, shown only once
                        // someone has actually confirmed. The button below does the
                        // confirming; this line just reflects the shared count.
                        if (incident.confirmationCount > 0) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.incidents_confirmedBy,
                                        incident.confirmationCount,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(INCIDENT_DETAILS_CONFIRM_COUNT_TAG),
                            )
                        }
                    IncidentAction.None ->
                        Text(
                            text = stringResource(R.string.incidents_removeImportedExplanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    IncidentAction.Remove -> Unit
                }
            }
        },
        confirmButton = {
            when (action) {
                IncidentAction.Confirm ->
                    TextButton(
                        onClick = onConfirm,
                        // Live now that `incidents-confirm` is wired; disabled only
                        // while a confirmation is already in flight, so one press is
                        // one call.
                        enabled = confirmWired && !confirmInProgress,
                        modifier = Modifier.testTag(INCIDENT_DETAILS_CONFIRM_TAG),
                    ) {
                        Text(stringResource(R.string.incidents_verifyAction))
                    }
                IncidentAction.Remove ->
                    TextButton(
                        onClick = onRemove,
                        // One delete per press: the sheet outlives the press now,
                        // so without this a second tap during the round-trip would
                        // fire a second removal for the same incident.
                        enabled = !removeInProgress,
                        modifier = Modifier.testTag(INCIDENT_DETAILS_REMOVE_TAG),
                    ) {
                        Text(stringResource(R.string.incidents_removeAction))
                    }
                IncidentAction.None -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.incidents_close))
            }
        },
    )
}

/**
 * The same badge the map draws, at dialog size: category colour disc with the
 * white category glyph on it. Rendered from the SAME drawable table
 * ([incidentGlyphRes]) as the marker, so the thing you tapped and the thing you are
 * now reading about are visibly the same object.
 */
@Composable
private fun IncidentBadge(type: IncidentType) {
    Box(
        modifier =
            Modifier
                .size(BADGE_SIZE)
                .clip(CircleShape)
                .background(IncidentPalette.color(type)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(incidentGlyphRes(type)),
            // The category is already announced by the adjacent title text, so
            // the badge is decorative — a second reading of the same word is
            // noise to a screen-reader user.
            contentDescription = null,
            // The SAME contrast-chosen tint the map marker uses: a fixed white
            // glyph is unreadable on the amber and orange discs.
            tint = Color(IncidentMarkerStyle.glyphColorArgb(type)),
            modifier = Modifier.size(BADGE_GLYPH_SIZE),
        )
    }
}

/** The localized "x ago" line for a bucketed [age]. */
@Composable
private fun incidentAgeLabel(age: IncidentAge): String =
    when (age) {
        IncidentAge.JustNow -> stringResource(R.string.incidents_ageJustNow)
        is IncidentAge.Minutes -> stringResource(R.string.incidents_ageMinutes, age.minutes)
        is IncidentAge.Hours -> stringResource(R.string.incidents_ageHours, age.hours)
        is IncidentAge.Days -> stringResource(R.string.incidents_ageDays, age.days)
        IncidentAge.Unknown -> stringResource(R.string.incidents_ageUnknown)
    }

/** Dialog-sized badge, on the app's spacing scale like every other sized box. */
private val BADGE_SIZE = KccSpacing.s8

/**
 * The glyph inside it, at the SAME proportion of the badge the map marker uses
 * ([IncidentMarkerStyle.GLYPH_SCALE]). Derived rather than written as a literal
 * so the sheet's badge and the marker it was tapped from cannot drift into
 * looking like two different objects if that proportion is ever retuned.
 */
private val BADGE_GLYPH_SIZE = BADGE_SIZE * IncidentMarkerStyle.GLYPH_SCALE
