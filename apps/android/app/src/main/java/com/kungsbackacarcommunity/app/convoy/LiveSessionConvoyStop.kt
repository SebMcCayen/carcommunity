package com.kungsbackacarcommunity.app.convoy

/**
 * The convoy backend action a "stop live session" choice maps to.
 *
 * These are the SAME two convoy exits the bar already offers, reused here so the
 * "you are ending your live session while a convoy is active" prompt cannot
 * drift from the convoy bar's own semantics:
 *
 *  - [EndConvoy] is `convoy-end` — the LEADER-only action that ends the convoy
 *    for EVERYONE (see [ConvoyCoordinator.end]).
 *  - [LeaveConvoy] is `convoy-leave` — removes only the CALLER; the convoy
 *    carries on for the remaining members and, when the caller is the leader,
 *    leadership transfers (see [ConvoyCoordinator.leave]). When too few members
 *    are left to carry it, the server ends the convoy as part of the leave.
 */
enum class ConvoyStopAction {
    EndConvoy,
    LeaveConvoy,
}

/**
 * What ending the live session should do about a convoy.
 *
 * The whole point of #726's invariant — "if you have a convoy active you should
 * also have a live session ongoing" — is that ending the live session is the
 * natural decision point for the convoy: you cannot keep the convoy without the
 * session that feeds it. So stopping the session with an active convoy must
 * never silently strand or silently end the convoy; it asks.
 */
sealed interface LiveSessionStopPlan {
    /** No active convoy — end the live session immediately, no dialog. */
    data object StopNow : LiveSessionStopPlan

    /**
     * A convoy is being driven — ask the user what to do with it before the
     * session ends. [exitChoice] is the SAME [ConvoyBar.exitChoice] the convoy
     * bar uses, so the owner/non-owner + survival semantics are decided in one
     * place; [LiveSessionConvoyStop.actionsFor] turns it into the ordered set of
     * convoy actions the dialog offers.
     */
    data class AskConvoy(val exitChoice: ConvoyExitChoice) : LiveSessionStopPlan
}

/**
 * Pure decision logic bridging "end my live session" and an active convoy.
 *
 * Kept off the composable and expressed over plain values so the owner/non-owner
 * and "who is left" rules are JVM-unit-testable: the composable only renders what
 * this returns and wires each [ConvoyStopAction] to its callable.
 */
object LiveSessionConvoyStop {
    /**
     * Whether ending the live session must first ask about the convoy, and — if
     * so — which exit(s) to offer.
     *
     * Gated on the convoy being genuinely ACTIVE (being driven), which is the
     * convoy that #726's invariant ties the live session to. A convoy that is
     * only [ConvoyStatus.Forming] has not started a shared drive, so ending an
     * (unrelated) solo session must not be held up by it — [StopNow].
     *
     * @param inActiveConvoy the caller is in a convoy whose status is Active.
     * @param viewerIsOwner the caller leads that convoy.
     * @param acceptedMemberCount accepted members INCLUDING the caller (so the
     *   count left behind is one less), exactly as [ConvoyBar.exitChoice] expects.
     */
    fun plan(
        inActiveConvoy: Boolean,
        viewerIsOwner: Boolean,
        acceptedMemberCount: Int,
    ): LiveSessionStopPlan =
        if (!inActiveConvoy) {
            LiveSessionStopPlan.StopNow
        } else {
            LiveSessionStopPlan.AskConvoy(
                ConvoyBar.exitChoice(viewerIsOwner, acceptedMemberCount),
            )
        }

    /**
     * The ordered convoy actions the stop dialog offers for an [exitChoice].
     *
     * Owner-or-not is already decided by [ConvoyBar.exitChoice], so this only
     * shapes it into buttons — a non-owner can NEVER be handed [EndConvoy]:
     *
     *  - [ConvoyExitChoice.LeaveOrEnd]  → End the convoy, or leave it running.
     *  - [ConvoyExitChoice.EndOnly]     → End only (leaving would end it anyway).
     *  - [ConvoyExitChoice.LeaveOnly]   → Leave only (the others carry on).
     *  - [ConvoyExitChoice.LeaveEndsConvoy] → Leave only, but leaving ends it.
     *
     * The dialog always ALSO offers a "keep sharing" dismissal (do nothing, the
     * session and convoy both carry on); that is the composable's cancel path and
     * not one of these convoy actions.
     */
    fun actionsFor(exitChoice: ConvoyExitChoice): List<ConvoyStopAction> =
        when (exitChoice) {
            ConvoyExitChoice.LeaveOrEnd ->
                listOf(ConvoyStopAction.EndConvoy, ConvoyStopAction.LeaveConvoy)
            ConvoyExitChoice.EndOnly -> listOf(ConvoyStopAction.EndConvoy)
            ConvoyExitChoice.LeaveOnly -> listOf(ConvoyStopAction.LeaveConvoy)
            ConvoyExitChoice.LeaveEndsConvoy -> listOf(ConvoyStopAction.LeaveConvoy)
        }
}
