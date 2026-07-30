package com.kungsbackacarcommunity.app.update

import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable

/** The outcome of one update check. */
data class AppUpdateCheckResult(
    val decision: AppUpdateDecision,
    /**
     * The reading the decision was made on, or null when there was none. The
     * shell needs it for one thing only: the versionCode to record when the
     * prompt is dismissed.
     */
    val availability: AppUpdateAvailability?,
) {
    companion object {
        val NONE = AppUpdateCheckResult(AppUpdateDecision.NONE, null)
    }
}

/**
 * One update check, start to finish, with no way to fail loudly.
 *
 * The shell calls this exactly ONCE per app session — a cold start is the
 * natural moment to update, and an update prompt that arrives mid-use is just
 * noise — and the throttle in [AppUpdatePolicy] then decides whether the
 * result is worth showing at all. So Play is asked once per launch, never per
 * screen and never per recomposition.
 */
object AppUpdateCheck {

    /**
     * @param source null when the Play update API is not available on this
     *   device at all, which yields [AppUpdateCheckResult.NONE].
     */
    suspend fun run(
        source: AppUpdateSource?,
        dismissal: AppUpdateDismissal?,
        nowMillis: Long,
    ): AppUpdateCheckResult {
        if (source == null) return AppUpdateCheckResult.NONE

        // PlayAppUpdateSource already swallows its own failures, but this is
        // the boundary that has to hold: a source that throws — a future
        // implementation, a Play library change, an OEM Play build that
        // behaves differently — must still leave the app exactly as it was.
        // runCatchingCancellable, not runCatching, so a cancelled coroutine
        // still unwinds instead of being reported as "no update".
        val availability =
            runCatchingCancellable { source.fetch() }.getOrNull()
                ?: return AppUpdateCheckResult.NONE

        return AppUpdateCheckResult(
            decision = AppUpdatePolicy.decide(availability, dismissal, nowMillis),
            availability = availability,
        )
    }
}
