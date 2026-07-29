package com.kungsbackacarcommunity.app.update

/** What, if anything, the shell should put in front of the user. */
enum class AppUpdateDecision {
    /** Nothing to show — the common case. */
    NONE,

    /** A newer build exists. Dismissible prompt: "Uppdatera" / "Inte nu". */
    OPTIONAL,

    /**
     * This build is older than the oldest still-supported one. Non-dismissible.
     * INERT BY DEFAULT: it only ever fires when an admin has deliberately set
     * a non-zero `minimumSupportedVersionCode`, and the default written by
     * `admin.setAppVersion` is 0.
     */
    REQUIRED,
}

/** A recorded "not now" tap: which target version, and when. */
data class AppUpdateDismissal(
    /** The [AppVersionConfig.latestVersionCode] that was being offered. */
    val versionCode: Int,
    val atMillis: Long,
)

/**
 * The pure decision behind the in-app update prompt. No Android, no Firebase,
 * no clock of its own — everything it needs is a parameter, so the whole
 * policy is unit-testable.
 */
object AppUpdatePolicy {

    /**
     * How long a "not now" silences the prompt FOR THE SAME target version.
     *
     * The dismissal policy in one sentence: tapping "Inte nu" hides the prompt
     * for that version for a week, and a NEWER release re-prompts immediately
     * rather than waiting the week out. So the prompt cannot reappear on the
     * next screen, or on the next cold start, or the next day — but it also
     * cannot be silenced forever by one tap, and a genuinely new release is
     * never held back by a dismissal aimed at an older one.
     */
    const val DISMISS_SUPPRESSION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * Decides what to show.
     *
     * @param config the server-held record, or null when it is missing,
     *   unreadable or malformed — which always yields [AppUpdateDecision.NONE].
     * @param currentVersionCode this build's `BuildConfig.VERSION_CODE`.
     * @param dismissal the last recorded "not now", or null.
     * @param nowMillis wall-clock now.
     */
    fun decide(
        config: AppVersionConfig?,
        currentVersionCode: Int,
        dismissal: AppUpdateDismissal?,
        nowMillis: Long,
    ): AppUpdateDecision {
        // No config, no prompt. Every failure path upstream (offline, denied
        // read, absent document, garbage values) collapses to this null, so
        // the feature's failure mode is "behave as if it were not there".
        if (config == null) return AppUpdateDecision.NONE

        // The blocking check comes first and is NOT dismissible. It can only
        // trigger on a deliberately raised minimum; AppVersionConfig.fromStored
        // has already discarded a minimum that no published build could meet.
        if (currentVersionCode < config.minimumSupportedVersionCode) {
            return AppUpdateDecision.REQUIRED
        }

        // Integer comparison, never a version-NAME string comparison.
        // Equal or newer (a local debug build ahead of Play, say) shows nothing.
        if (currentVersionCode >= config.latestVersionCode) return AppUpdateDecision.NONE

        if (dismissal != null && dismissal.versionCode >= config.latestVersionCode) {
            // Elapsed is negative if the device clock moved backwards since the
            // dismissal; that still counts as "recently dismissed", because the
            // safe reading of an untrustworthy clock is to nag less, not more.
            val elapsed = nowMillis - dismissal.atMillis
            if (elapsed < DISMISS_SUPPRESSION_MILLIS) return AppUpdateDecision.NONE
        }

        return AppUpdateDecision.OPTIONAL
    }
}
