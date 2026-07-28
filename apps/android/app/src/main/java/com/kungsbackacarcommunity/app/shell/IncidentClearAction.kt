package com.kungsbackacarcommunity.app.shell

import android.content.Context
import com.kungsbackacarcommunity.app.crownhunt.CrownLocation
import com.kungsbackacarcommunity.app.incidents.ClearOutcome
import com.kungsbackacarcommunity.app.incidents.IncidentClearFix
import com.kungsbackacarcommunity.app.incidents.IncidentReportController
import java.time.Instant

/**
 * Takes the position a clear vote is made from: a FRESH, high-accuracy fix, right
 * now.
 *
 * Reuses [CrownLocation.currentFix] rather than the cheaper
 * `CurrentLocation.lastKnown`, and the difference is the whole point. A route
 * origin is happy with a cached fix that may be minutes old; a clear vote is
 * evidence that this member is looking at the spot RIGHT NOW, and the backend
 * refuses a stale one. `CrownLocation` already asks for exactly that — a current
 * high-accuracy fix carrying its own timestamp, its accuracy, and `isMock` —
 * because a Kronjakt claim needs the same thing, so this is one position source
 * rather than a second near-copy of it.
 *
 * [IncidentClearFix.capturedAtIso] is the FIX's own timestamp, never "now":
 * stamping it with the moment we happened to read it would hide precisely the
 * staleness the server checks for.
 *
 * Null when no fix is available (permission off, no GPS, config-less build), in
 * which case nothing is sent — a vote without a position is not a weaker vote,
 * it is not a vote.
 */
suspend fun currentIncidentClearFix(context: Context): IncidentClearFix? {
    val fix = CrownLocation.currentFix(context.applicationContext) ?: return null
    return IncidentClearFix(
        latitude = fix.latitude,
        longitude = fix.longitude,
        capturedAtIso = Instant.ofEpochMilli(fix.recordedAtMillis).toString(),
        accuracyMeters = fix.accuracyMeters,
        // Reported truthfully and never suppressed: the backend treats it as a
        // one-way signal, so an honest client loses nothing and a dishonest one
        // gains nothing by lying.
        isMock = fix.isMock == true,
    )
}

/**
 * The details sheet's "Nej, den är borta" action: votes that an incident is GONE
 * and reports the outcome so the caller can raise the right snackbar.
 *
 * Lives outside the composable for the same reason [runIncidentConfirmation] and
 * [runIncidentRemoval] do — the SHEET LIFETIME rule, which is easy to get wrong
 * and impossible to assert from inside `AuthenticatedApp`:
 *
 *  - **Voted** (including an idempotent repeat, and including the case where the
 *    vote only FADED the incident rather than removing it) → the tap is consumed
 *    and the sheet closes. The action is terminal for this member: they have said
 *    their piece, and re-opening later shows the updated tallies. Leaving a
 *    now-inert button live would only invite a second tap that does nothing.
 *
 *  - **Rejected or failed** → the tap is left alone, so the sheet stays open.
 *    That is exactly when the user needs it: a rejection carries an actionable
 *    reason ("drive closer"), and leaving the sheet up is what lets them act on
 *    it and retry. Closing on a rejection would hide both the reason and the
 *    incident it was about.
 *
 *  - **NoLocation** → also leaves the sheet open. Nothing was sent, and the user
 *    may be about to turn location on.
 *
 * The voted case consumes the tap ONLY IF the pending tap is still the incident
 * that was voted on. `incidentTap` is a single slot, not a queue, so an
 * unconditional consume could clear a DIFFERENT incident the user tapped while
 * this call was in flight — closing a sheet nobody asked to close. Same guard as
 * [runIncidentConfirmation].
 */
suspend fun runIncidentClearVote(
    controller: IncidentReportController,
    mapSurface: MapSurface,
    incidentId: String,
    fixProvider: suspend () -> IncidentClearFix?,
): ClearOutcome {
    val outcome = controller.reportCleared(incidentId, fixProvider)
    if (outcome is ClearOutcome.Success && mapSurface.incidentTap.value == incidentId) {
        mapSurface.consumeIncidentTap()
    }
    return outcome
}
