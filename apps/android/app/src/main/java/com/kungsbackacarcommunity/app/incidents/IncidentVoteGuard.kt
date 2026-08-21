package com.kungsbackacarcommunity.app.incidents

/**
 * Tracks which incident votes (confirm / clear) are IN FLIGHT, keyed by incident
 * id, so the optimistic UI cannot fire a SECOND vote on the same incident while the
 * first — its GPS fix and callable — is still running.
 *
 * This is needed precisely because the optimistic UI dismisses the details sheet
 * the INSTANT a vote is cast. A guard scoped to the open sheet (a boolean keyed to
 * the tapped incident) would reset the moment the sheet closed, so a member who
 * re-opened the same marker could fire a duplicate vote mid-flight. Keying by
 * incident id, in a holder that OUTLIVES the sheet, closes that window; the id is
 * registered before the vote fires and cleared on completion, success or rollback.
 *
 * Deliberately Compose-free and unit-testable. Not thread-safe by design: it is
 * touched only from the single UI coroutine scope (the main dispatcher), the one
 * writer, so no synchronisation is warranted and none is implied.
 */
class IncidentVoteGuard {
    private val inFlight = mutableSetOf<String>()

    /**
     * Registers a vote for [incidentId] as in flight and returns true when the
     * caller may PROCEED, or false when a vote for the same incident is ALREADY in
     * flight (the duplicate must be dropped). Mirrors [MutableSet.add]'s contract:
     * the first caller wins and every repeat is refused until the matching [end].
     */
    fun tryBegin(incidentId: String): Boolean = inFlight.add(incidentId)

    /** Marks the vote for [incidentId] finished — success OR rollback. */
    fun end(incidentId: String) {
        inFlight.remove(incidentId)
    }

    /** Whether a vote for [incidentId] is currently in flight. */
    fun isInFlight(incidentId: String): Boolean = incidentId in inFlight
}
