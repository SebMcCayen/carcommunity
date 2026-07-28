package com.kungsbackacarcommunity.app.convoy

/**
 * What actually became of the invite a notification tap was aiming at.
 *
 * THE RACE THIS EXISTS FOR
 * ------------------------
 * The inbox decides whether an invite row looks actionable from the convoy
 * snapshot the shell happens to be holding, which may be minutes old. Between
 * the row rendering and the tap landing here, the owner can end the convoy, or
 * the invite can be answered on another device. Arriving on a convoy list with
 * no matching invite on it — and no explanation — is precisely the "tapping does
 * nothing" complaint in a new costume, so the destination re-derives the outcome
 * from ITS OWN freshly loaded list and says what happened.
 *
 * This is also why the inbox is allowed to be optimistic: a stale snapshot can
 * only ever send someone here, and here always tells the truth.
 *
 * Pure and total — no Compose, no I/O.
 */
enum class ConvoyInviteDeepLinkOutcome {
    /** The invite is on the list, waiting. Nothing to explain. */
    PENDING,

    /** The convoy ended before the tap landed. */
    ENDED,

    /** The invite was accepted or declined already (here or elsewhere). */
    ANSWERED,

    /**
     * The convoy is not in the caller's list at all — it fell past the list's
     * bound, or they are no longer a member of it. Indistinguishable from the
     * outside, so it gets its own honest "not available any more" wording rather
     * than being guessed into one of the two above.
     */
    GONE,
}

object ConvoyInviteDeepLink {
    /**
     * The outcome to show for [convoyId], or null when there is nothing to say —
     * no deep link at all, or the list has not loaded yet (a verdict on a list
     * we do not have would be a guess, and PENDING is the only safe silence).
     */
    fun outcome(convoyId: String?, status: ConvoyListStatus): ConvoyInviteDeepLinkOutcome? {
        val id = convoyId?.takeIf { it.isNotBlank() } ?: return null
        val loaded = status as? ConvoyListStatus.Loaded ?: return null
        val convoy = loaded.convoy(id)
        return when {
            // ENDED is checked before the pending list on purpose. The two come
            // from the same response and should agree, but if they ever don't,
            // the failure that matters is offering Accept on a convoy that is
            // over — so the convoy's own status wins.
            convoy?.status == ConvoyStatus.Ended -> ConvoyInviteDeepLinkOutcome.ENDED
            loaded.pendingInvites.any { it.convoyId == id } -> ConvoyInviteDeepLinkOutcome.PENDING
            convoy == null -> ConvoyInviteDeepLinkOutcome.GONE
            // Present, live, but not in pendingInvites: the backend builds that
            // list as "not ended AND my invite is still 'invited'", so the only
            // way to be here is an invite that has been answered.
            else -> ConvoyInviteDeepLinkOutcome.ANSWERED
        }
    }

    /** True when the outcome is worth putting a notice on screen for. */
    fun needsNotice(outcome: ConvoyInviteDeepLinkOutcome?): Boolean =
        outcome != null && outcome != ConvoyInviteDeepLinkOutcome.PENDING

    /**
     * The pending-invite list with the deep-linked convoy pulled to the front.
     *
     * A member with several open invites would otherwise have to hunt for the
     * one they just tapped. Order-preserving for everything else, and a no-op
     * when the id is absent, blank, or not on the list — so it can never drop or
     * duplicate a row.
     */
    fun inviteesFirst(
        invites: List<ConvoySummary>,
        convoyId: String?,
    ): List<ConvoySummary> {
        val id = convoyId?.takeIf { it.isNotBlank() } ?: return invites
        if (invites.size < 2 || invites.none { it.convoyId == id }) return invites
        return invites.sortedBy { if (it.convoyId == id) 0 else 1 }
    }
}
