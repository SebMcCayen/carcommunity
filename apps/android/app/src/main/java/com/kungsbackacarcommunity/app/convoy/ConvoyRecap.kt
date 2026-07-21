package com.kungsbackacarcommunity.app.convoy

/**
 * Pure render-state for the ended-convoy RECAP — who was in it, how long it ran,
 * and how far when the backend has it.
 *
 * A convoy that has ended stores a [ConvoySummaryStats] every member reads
 * (`convoy.end` computes it). The detail screen already showed the bare figures;
 * this adds the missing "who was in it" by joining `summary.participantUids` to
 * the roster so the recap can show faces and names, not just a count.
 *
 * Kept out of the Composable so the present / partial / absent-field decisions
 * (no summary at all, a participant uid with no matching roster entry, a null
 * distance) are JVM-unit-testable — mirroring [ConvoyBar] and [ConvoyFormat].
 *
 * ## Known backend gap this degrades around
 * `summary.distanceMeters` is NULL in the current backend — `convoy.end` does not
 * aggregate a shared route (see `ConvoySummaryStats` and the backend
 * `computeConvoySummary`, which hard-codes `distanceMeters: null`). [distanceMeters]
 * is therefore null in practice today, and the UI renders "not available" rather
 * than a fake "0 km". Populating it is a BACKEND change (a convoy-scoped route
 * roll-up) and is deliberately not attempted here.
 */
data class ConvoyRecapState(
    val durationSeconds: Long,
    /**
     * The people the recap can name/picture, joined from `participantUids` to the
     * roster. MAY be shorter than [participantCount] (or empty) when a participant
     * is no longer in the roster or the uid list was not populated — the count row
     * still tells the honest total in that case.
     */
    val participants: List<ConvoyRecapParticipant>,
    /** Authoritative participant total from the stored summary. */
    val participantCount: Int,
    /** Total distance in metres, or null when the backend did not populate it. */
    val distanceMeters: Double?,
)

/** One recap participant, named/pictured from the roster where possible. */
data class ConvoyRecapParticipant(
    val uid: String,
    val displayName: String?,
    val avatarPath: String?,
)

object ConvoyRecap {
    /**
     * The recap to show for [convoy], or null when there is nothing to recap —
     * the convoy has not ended, or no summary was stored. Returning null (rather
     * than an empty card) keeps the caller from rendering a recap for a live
     * convoy or an empty shell for a missing summary.
     */
    fun stateFor(convoy: ConvoySummary): ConvoyRecapState? {
        if (convoy.status != ConvoyStatus.Ended) return null
        val summary = convoy.summary ?: return null

        val membersByUid = convoy.members.associateBy { it.uid }
        val participants =
            summary.participantUids.map { uid ->
                val member = membersByUid[uid]
                ConvoyRecapParticipant(
                    uid = uid,
                    displayName = member?.displayName,
                    avatarPath = member?.avatarPath,
                )
            }

        return ConvoyRecapState(
            durationSeconds = summary.durationSeconds,
            participants = participants,
            // The stored count is authoritative; never let a shorter derived
            // roster understate how many were actually in the convoy.
            participantCount = maxOf(summary.participantCount, participants.size),
            distanceMeters = summary.distanceMeters,
        )
    }
}
