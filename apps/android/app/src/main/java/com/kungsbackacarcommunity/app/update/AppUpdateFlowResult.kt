package com.kungsbackacarcommunity.app.update

import android.app.Activity

/** What Play's update flow reported when it handed control back to the app. */
enum class AppUpdateFlowOutcome {
    /**
     * The member accepted and Play took it from there. Nothing to say — the
     * download runs in the background, and the restart offer arrives on its
     * own when it finishes.
     */
    ACCEPTED,

    /**
     * The member backed out of Play's own consent sheet. SILENT BY DESIGN: a
     * decline is an answer, not a failure, and the suppression window was
     * already recorded when Update was pressed, so nobody is asked again on
     * the next launch.
     */
    DECLINED,

    /**
     * Play could not run the flow (its `RESULT_IN_APP_UPDATE_FAILED`, or any
     * other unexpected code). The in-app route is out, so the member is handed
     * to the Play listing — the same recovery as a flow that never started.
     */
    FAILED,
}

/**
 * Reading the result code Play's update flow returns.
 *
 * Split out from the shell because it is the branch that decides whether
 * anyone is bothered: pure, exhaustive, and unit-testable against Play's own
 * constants rather than left as an inline negated comparison.
 */
object AppUpdateFlowResult {

    fun read(resultCode: Int): AppUpdateFlowOutcome =
        when (resultCode) {
            Activity.RESULT_OK -> AppUpdateFlowOutcome.ACCEPTED
            Activity.RESULT_CANCELED -> AppUpdateFlowOutcome.DECLINED
            // Play's RESULT_IN_APP_UPDATE_FAILED, and anything else that is
            // neither an acceptance nor a decline: treated as a failure so an
            // unrecognised code errs towards offering the store listing rather
            // than silently dropping the update.
            else -> AppUpdateFlowOutcome.FAILED
        }
}
