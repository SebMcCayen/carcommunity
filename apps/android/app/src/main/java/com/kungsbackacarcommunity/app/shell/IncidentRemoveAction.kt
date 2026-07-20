package com.kungsbackacarcommunity.app.shell

import com.kungsbackacarcommunity.app.incidents.IncidentReportController

/**
 * The details sheet's "remove" action: deletes the incident and reports whether
 * the backend accepted it, so the caller can raise the right snackbar.
 *
 * The point of this living outside the composable is the SHEET LIFETIME rule,
 * which is easy to get wrong and impossible to assert from inside
 * `AuthenticatedApp`:
 *
 *  - **Accepted** → the tap is consumed and the sheet closes. The removal has
 *    already dropped the incident from `nearbyIncidents`, so the sheet would
 *    close on its own anyway; consuming as well ties the sheet's lifetime to the
 *    action as well as to the data, so a list that somehow still holds the row
 *    cannot leave a sheet open describing an incident that is gone.
 *
 *  - **Rejected** → the tap is left alone, so the sheet stays open. The incident
 *    is still on the map (the controller only prunes its list after the backend
 *    accepts), and the user is still looking at the thing they failed to remove,
 *    which is exactly when they want to try again. Closing here — as this action
 *    used to, before the outcome was even known — made a removal that never
 *    happened look identical to one that did, and took the incident away from
 *    the user before they could retry it.
 *
 * The accepted case consumes the tap ONLY IF the pending tap is still the
 * incident that was removed. `incidentTap` is a single slot, not a queue, so an
 * unconditional consume clears whatever happens to be in it by the time the
 * backend answers — which is not necessarily what was asked about. Keeping the
 * sheet open across the round-trip is what opens that window: the user can
 * dismiss mid-flight, tap a DIFFERENT incident, and have the first removal land
 * afterwards and close the second incident's sheet, an incident nobody asked to
 * remove and which is still on the map.
 */
suspend fun runIncidentRemoval(
    controller: IncidentReportController,
    mapSurface: MapSurface,
    incidentId: String,
): Boolean {
    val removed = controller.remove(incidentId)
    if (removed && mapSurface.incidentTap.value == incidentId) {
        mapSurface.consumeIncidentTap()
    }
    return removed
}
