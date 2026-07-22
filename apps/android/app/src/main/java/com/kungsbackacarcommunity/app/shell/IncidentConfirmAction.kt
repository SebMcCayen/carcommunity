package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.incidents.ConfirmOutcome
import com.kungsbackacarcommunity.app.incidents.IncidentReportController

/**
 * The details sheet's "still there?" action: confirms SOMEONE ELSE'S incident
 * and reports the outcome so the caller can raise the right snackbar.
 *
 * Lives outside the composable for the same reason [runIncidentRemoval] does —
 * the SHEET LIFETIME rule, which is easy to get wrong and impossible to assert
 * from inside `AuthenticatedApp`:
 *
 *  - **Confirmed** (including an idempotent repeat, `alreadyConfirmed`) → the tap
 *    is consumed and the sheet closes. The action is terminal: the incident's
 *    life is extended and the shared count is bumped, and re-opening later shows
 *    the fresh "confirmed by N". Closing gives the user clear closure on a
 *    one-shot action rather than leaving a now-redundant button live.
 *
 *  - **Failed** → the tap is left alone, so the sheet stays open. The user is
 *    still looking at the incident they failed to confirm, which is exactly when
 *    they want to retry.
 *
 * The confirmed case consumes the tap ONLY IF the pending tap is still the
 * incident that was confirmed. `incidentTap` is a single slot, not a queue, so an
 * unconditional consume could clear a DIFFERENT incident the user tapped while
 * this call was in flight — closing a sheet nobody asked to close. Same guard as
 * [runIncidentRemoval].
 */
suspend fun runIncidentConfirmation(
    controller: IncidentReportController,
    mapSurface: MapSurface,
    incidentId: String,
): ConfirmOutcome {
    val outcome = controller.confirm(incidentId)
    if (outcome is ConfirmOutcome.Success && mapSurface.incidentTap.value == incidentId) {
        mapSurface.consumeIncidentTap()
    }
    return outcome
}
