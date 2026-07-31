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

/** Test tag on the "no, it's gone" clear-vote action. */
const val INCIDENT_DETAILS_CLEAR_TAG = "incident_details_clear"

/** Test tag on the "reported gone by N" line. */
const val INCIDENT_DETAILS_CLEARED_COUNT_TAG = "incident_details_cleared_count"

/** Test tag on the line explaining why the clear vote is unavailable. */
const val INCIDENT_DETAILS_CLEAR_BLOCKED_TAG = "incident_details_clear_blocked"

/**
 * The sheet opened by TAPPING an incident marker on the map.
 *
 * Answers the three things the badge itself cannot — what category it is, how
 * long ago it was reported, and where it came from — and then asks the ONE
 * question that keeps the shared map honest: **"Är den kvar?"**
 *
 *  - someone else's member report → BOTH answers are offered.
 *    **[Ja, den är kvar]** confirms it (`incidents-confirm`), extending its life
 *    and bumping the shared confirmation count. **[Nej, den är borta]** votes it
 *    gone (`incidents-reportCleared`).
 *
 *    The two are deliberately NOT symmetrical in consequence, and the sheet does
 *    not pretend otherwise. A confirmation is cheap and reversible. A clear vote
 *    can take a marker off every other driver's map, so the backend makes it
 *    earn that: the voter must be physically near the incident, and one vote only
 *    FADES the incident — it takes two net clear votes (or the original
 *    reporter) to remove it. That is why the sheet shows both counts at once
 *    rather than a single verdict: whoever reads this is about to drive into the
 *    spot, and "3 say it's there, 1 says it's gone" is more use to them than
 *    somebody else's arithmetic.
 *
 *  - your own member report → the existing remove action, which IS wired
 *    (`incidents-remove`). You are never offered "still there?" on your own
 *    report, and remove is the more direct route to the same outcome, so the
 *    clear vote is not duplicated onto it.
 *  - an imported Trafikverket row → neither, with a line saying why. The backend
 *    rejects removing and clearing imported incidents for everyone, admins
 *    included.
 *
 * WHEN THE CLEAR VOTE CANNOT BE OFFERED the button is rendered DISABLED with the
 * real reason beside it ("Kör närmare", "Uppgiften kommer från Trafikverket")
 * rather than hidden. A hidden control teaches nothing and looks like a bug; an
 * enabled one that always fails is worse. [ClearVoteEligibility] decides, purely
 * and unit-tested.
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
    // "Nej, den är borta". Null in a build/host that has no clear-vote wiring;
    // the action is then simply not rendered rather than rendered dead.
    onReportCleared: (() -> Unit)? = null,
    // Whether this viewer may actually vote it gone right now, and if not, why.
    // Defaults to NoLocation so a host that forgets to supply a position renders
    // an honest "location unavailable" rather than an action that always fails.
    clearEligibility: ClearVoteEligibility = ClearVoteEligibility.NoLocation,
    // True while a removal is in flight. The sheet now stays open across the
    // round-trip (it closes when the incident leaves the map, not when the
    // button is pressed), which would otherwise leave the button live long
    // enough to fire a second delete for the same incident.
    removeInProgress: Boolean = false,
    // True while a confirmation is in flight — same one-call-per-press guard as
    // removeInProgress, so a double-tap cannot fire two `incidents-confirm` calls.
    confirmInProgress: Boolean = false,
    // True while a clear vote is in flight — same one-call-per-press guard.
    clearInProgress: Boolean = false,
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
                IncidentBadge(type = incident.type, reportedCleared = incident.reportedCleared)
                Text(text = label, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s2)) {
                // Trafikverket rows are timed off their upstream original post
                // time, not our sync time; when that is missing the age line is
                // hidden entirely (ageDisplay returns null) rather than showing a
                // misleading "x min ago". Member reports are unchanged.
                IncidentDetails.ageDisplay(incident, nowMillis)?.let { age ->
                    Text(
                        text = incidentAgeLabel(age),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
                // BOTH tallies, always, whenever either is non-zero — never a
                // single netted verdict. The person reading this is about to
                // drive into the spot, and two honest numbers serve them better
                // than one conclusion drawn on their behalf.
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
                if (incident.clearedCount > 0) {
                    Text(
                        text = stringResource(R.string.incidents_clearedBy, incident.clearedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(INCIDENT_DETAILS_CLEARED_COUNT_TAG),
                    )
                }
                // The standing caution on a faded marker: it may well still be
                // there, so the advice is "take care", never "it's gone".
                if (incident.reportedCleared) {
                    Text(
                        text = stringResource(R.string.incidents_clearedMarkerHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (action) {
                    IncidentAction.Confirm -> {
                        // The question itself, asked once, directly above the two
                        // answers in the button row.
                        Text(
                            text = stringResource(R.string.incidents_stillHereQuestion),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // WHY the "no" answer is unavailable, when it is. Shown
                        // instead of hiding the button, so the user learns what
                        // would make it work rather than wondering where it went.
                        val blockedReason =
                            when (clearEligibility) {
                                ClearVoteEligibility.Available -> null
                                ClearVoteEligibility.TooFar -> R.string.incidents_clearedTooFar
                                ClearVoteEligibility.NoLocation ->
                                    R.string.incidents_clearedNoLocation
                                ClearVoteEligibility.ImportedSource ->
                                    R.string.incidents_clearedImportedExplanation
                            }
                        if (blockedReason != null) {
                            Text(
                                text = stringResource(blockedReason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(INCIDENT_DETAILS_CLEAR_BLOCKED_TAG),
                            )
                        }
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
                        Text(stringResource(R.string.incidents_stillHereYes))
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
            // "Nej, den är borta" sits beside Close rather than opposite the
            // confirm action: both are answers to the same question, and putting
            // the destructive-sounding one in the primary slot would invite the
            // reflex tap this whole design is built to survive.
            Row(horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
                if (action == IncidentAction.Confirm && onReportCleared != null) {
                    TextButton(
                        onClick = onReportCleared,
                        // Rendered even when it cannot be used — disabled, with the
                        // reason above — because a missing button explains nothing.
                        enabled =
                            clearEligibility == ClearVoteEligibility.Available && !clearInProgress,
                        modifier = Modifier.testTag(INCIDENT_DETAILS_CLEAR_TAG),
                    ) {
                        Text(stringResource(R.string.incidents_stillHereNo))
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
 * The same badge the map draws, at dialog size: the category disc with its
 * contrast-chosen glyph on it, washed out identically when the incident has been
 * reported gone. Rendered from the SAME drawable table ([incidentGlyphRes]) and
 * the SAME colour functions as the marker, so the thing you tapped and the thing
 * you are now reading about are visibly the same object in the same state.
 */
@Composable
private fun IncidentBadge(type: IncidentType, reportedCleared: Boolean) {
    Box(
        modifier =
            Modifier
                .size(BADGE_SIZE)
                .clip(CircleShape)
                // The SAME washed-out disc the map marker uses when the incident
                // has been reported gone, computed by the same pure function, so
                // the badge you tapped and the badge you are now reading about
                // cannot end up in different states.
                .background(Color(IncidentMarkerStyle.discColorArgb(type, reportedCleared))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(incidentGlyphRes(type)),
            // The category is already announced by the adjacent title text, so
            // the badge is decorative — a second reading of the same word is
            // noise to a screen-reader user.
            contentDescription = null,
            // The SAME contrast-chosen tint the map marker uses: a fixed white
            // glyph is unreadable on the amber and orange discs, and on EVERY
            // faded disc.
            tint = Color(IncidentMarkerStyle.glyphColorArgb(type, reportedCleared)),
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
