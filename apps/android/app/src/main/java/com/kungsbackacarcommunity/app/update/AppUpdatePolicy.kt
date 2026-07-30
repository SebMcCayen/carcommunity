package com.kungsbackacarcommunity.app.update

/** What, if anything, the shell should put in front of the user. */
enum class AppUpdateDecision {
    /** Nothing to show — the common case, and every failure path. */
    NONE,

    /**
     * A newer build is live on Play. Dismissible prompt, and accepting it runs
     * Play's FLEXIBLE flow: the download happens in the background and the app
     * stays usable throughout.
     */
    FLEXIBLE,

    /**
     * Play's IMMEDIATE (blocking) flow. Non-dismissible, and reached only for
     * a release whose `inAppUpdatePriority` was deliberately set to
     * [AppUpdateAvailability.MAX_PRIORITY], or to resume a blocking flow that
     * was already started. INERT BY DEFAULT: Play defaults every release's
     * priority to 0.
     */
    IMMEDIATE,

    /**
     * A flexible update finished downloading and needs a restart to install.
     * A quiet offer (snackbar), not a dialog: nothing is wrong, and the app
     * keeps working on the old code until the user is ready.
     */
    AWAITING_RESTART,
}

/** A recorded "not now" tap: which offered version, and when. */
data class AppUpdateDismissal(
    /** The [AppUpdateAvailability.availableVersionCode] that was being offered. */
    val versionCode: Int,
    val atMillis: Long,
)

/**
 * The pure decision behind the in-app update prompt. No Android, no Play, no
 * clock of its own — everything it needs is a parameter, so the whole policy
 * is unit-testable.
 */
object AppUpdatePolicy {

    /**
     * How long a "not now" silences the prompt FOR THE SAME offered version.
     *
     * The throttle in one sentence: tapping "Inte nu" hides the prompt for
     * that version for a week, and a NEWER release re-prompts immediately
     * rather than waiting the week out. So the prompt cannot reappear on the
     * next screen, or on the next cold start, or the next day — but it also
     * cannot be silenced forever by one tap, and a genuinely new release is
     * never held back by a dismissal aimed at an older one.
     */
    const val DISMISS_SUPPRESSION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * The `inAppUpdatePriority` at which the prompt stops being dismissible.
     * Play's maximum, so only a release explicitly published at the top of
     * the scale can block anyone.
     */
    const val IMMEDIATE_PRIORITY_THRESHOLD: Int = AppUpdateAvailability.MAX_PRIORITY

    /**
     * Decides what to show.
     *
     * @param availability what Play reported, or null when Play reported
     *   nothing, could not be reached, or does not know this install — which
     *   always yields [AppUpdateDecision.NONE].
     * @param dismissal the last recorded "not now", or null.
     * @param nowMillis wall-clock now.
     */
    fun decide(
        availability: AppUpdateAvailability?,
        dismissal: AppUpdateDismissal?,
        nowMillis: Long,
    ): AppUpdateDecision {
        // No reading, no prompt. Every failure path upstream (no Play install
        // context, offline, an API the device does not have, a thrown
        // InstallException) collapses to this null, so the feature's failure
        // mode is "behave as if it were not there".
        if (availability == null) return AppUpdateDecision.NONE

        // Already downloaded: offer the restart. Never throttled — the user
        // asked for this download, and an update sitting unused on disk is
        // worth one quiet reminder per session.
        if (availability.isDownloaded) return AppUpdateDecision.AWAITING_RESTART

        // A blocking flow that was interrupted must be finished; Play requires
        // it to be resumed, and a half-applied update is not a state to leave
        // a user in. Not dismissible, so not throttled.
        if (availability.isImmediateInProgress) return AppUpdateDecision.IMMEDIATE

        // The deliberately-raised, default-inert escalation. Set on the Play
        // release itself (inAppUpdatePriority), so it needs no backend value
        // and no admin action — and it is 0 unless someone means it.
        if (availability.priority >= IMMEDIATE_PRIORITY_THRESHOLD &&
            availability.isImmediateAllowed
        ) {
            return AppUpdateDecision.IMMEDIATE
        }

        // Play can report an update that the background flow may not install
        // (an asset-pack constraint, say). Offering it would produce a prompt
        // whose button cannot do anything.
        if (!availability.isFlexibleAllowed) return AppUpdateDecision.NONE

        if (dismissal != null && dismissal.versionCode >= availability.availableVersionCode) {
            // Elapsed is negative if the device clock moved backwards since the
            // dismissal; that still counts as "recently dismissed", because the
            // safe reading of an untrustworthy clock is to nag less, not more.
            val elapsed = nowMillis - dismissal.atMillis
            if (elapsed < DISMISS_SUPPRESSION_MILLIS) return AppUpdateDecision.NONE
        }

        return AppUpdateDecision.FLEXIBLE
    }
}
