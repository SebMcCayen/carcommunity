package com.kungsbackacarcommunity.app.events

import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether an events-list load failure is worth auto-filing as a public
 * GitHub issue (via `ClientErrorReporter` → `errors-reportClientError` →
 * `errors-onClientErrorReport`).
 *
 * ## Why a gate rather than a bare `report(...)` call
 *
 * The Past tab's error had ONE cause worth a bug report — a composite index the
 * client needs and the project has not deployed — and one cause that is not a
 * bug at all: the user is on a train with no signal. A snapshot listener
 * reports both through the same [EventsListState.Error]. Filing the second
 * would put a GitHub issue on the board every time someone opens Events in a
 * tunnel, and would burn the 30/hour per-user server budget that exists to
 * carry real faults.
 *
 * So the gate is on the Firestore status CODE:
 *  - [REPORTABLE_CODES] — structural, deploy-shaped faults that will not fix
 *    themselves and that nobody else is going to notice. Report these.
 *  - everything else (`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `CANCELLED`,
 *    `ABORTED`, `RESOURCE_EXHAUSTED`, or no code at all) — transient or
 *    environmental. Stay silent; the user's retry button is the right answer.
 *
 * ## Privacy
 *
 * The issues this feeds are WORLD-READABLE. Nothing here ever handles an
 * exception message, a uid, a document id, a query value, or any user text —
 * only a literal feature key, a literal app-authored sentence, and a Firestore
 * status name from a closed enum. Callers must keep it that way.
 *
 * ## Once per process
 *
 * [shouldReport] returns true at most once per feature key for the life of the
 * process. The backend already dedups by fingerprint, so extra reports would
 * change nothing on the board — but each one still costs a callable round trip
 * and a slice of the per-user rate limit, and the error state is re-entered on
 * every tab flip and every retry tap. One report per session per surface is
 * enough to prove the fault exists.
 */
object EventsErrorReporting {

    /** Stable feature key for the past/archive tab (backend fingerprint input). */
    const val FEATURE_PAST_LIST = "events.pastList"

    /** Stable feature key for the upcoming tab. */
    const val FEATURE_UPCOMING_LIST = "events.upcomingList"

    /** App-authored, PII-free summary for the past tab. */
    const val MESSAGE_PAST_LIST =
        "Events past/archive list listener failed (completed events, startsAt descending)"

    /** App-authored, PII-free summary for the upcoming tab. */
    const val MESSAGE_UPCOMING_LIST =
        "Events upcoming list listener failed (published events, startsAt ascending)"

    /**
     * Firestore status names worth an issue. `FAILED_PRECONDITION` is the
     * missing-composite-index signal; `PERMISSION_DENIED` is a rules gap. Both
     * mean the app is asking for something the project is not configured to
     * serve, which is exactly a bug — and the two need DIFFERENT fixes, which
     * is why the code travels with the report rather than being flattened into
     * one "events broke" message.
     */
    val REPORTABLE_CODES: Set<String> = setOf("FAILED_PRECONDITION", "PERMISSION_DENIED")

    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** True when [code] names a structural fault, ignoring case/underscore style. */
    fun isReportable(code: String?): Boolean {
        val normalized = code?.trim()?.uppercase()?.replace('-', '_') ?: return false
        return normalized in REPORTABLE_CODES
    }

    /**
     * True when this failure should be filed: a reportable [code] AND the first
     * such call for [feature] in this process. Has a side effect by design —
     * call it once, at the point of reporting.
     */
    fun shouldReport(feature: String, code: String?): Boolean =
        isReportable(code) && reported.add(feature)

    /** Test-only: clears the once-per-process latch. */
    fun resetForTest() = reported.clear()
}
