package com.kungsbackacarcommunity.app.update

/**
 * What Google Play reports about a newer build, normalized away from the Play
 * Core int codes so the decision that acts on it stays a pure, Play-free
 * module.
 *
 * Nothing here is maintained by hand anywhere. Play answers for the track the
 * user actually installed from, so an "update available" reading is true by
 * construction: there is no window in which the app announces a version that
 * Play cannot serve.
 */

/** Play's `UpdateAvailability`, reduced to the three cases we act on. */
enum class PlayUpdateState {
    /** No newer build, or Play does not know (both mean: say nothing). */
    NOTHING,

    /** A newer build is live on this install's track. */
    AVAILABLE,

    /**
     * An in-app update this app started earlier has not finished.
     * (Play's `DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`.)
     */
    IN_PROGRESS,
}

/** Play's `InstallStatus`, reduced to the three cases we act on. */
enum class PlayInstallState {
    /** Nothing in flight (unknown / installed / failed / cancelled). */
    IDLE,

    /** Pending, downloading or installing — already under way, leave it alone. */
    WORKING,

    /** Downloaded and waiting for the restart that installs it. */
    DOWNLOADED,
}

/**
 * @property availableVersionCode the versionCode Play is offering. Used only
 *   as the throttle key, never compared against this build — Play has already
 *   made the "is there something newer" judgement, and re-deciding it here
 *   from a number would reintroduce exactly the class of bug this replaces.
 * @property isFlexibleAllowed Play permits the background (non-blocking) flow.
 * @property isImmediateAllowed Play permits the blocking full-screen flow.
 * @property priority the release's `inAppUpdatePriority` (0..5), set on the
 *   Play release itself. 0 unless someone deliberately raised it.
 * @property isImmediateInProgress a blocking flow was started and not finished.
 * @property isDownloaded a flexible update is downloaded, pending a restart.
 */
data class AppUpdateAvailability(
    val availableVersionCode: Int,
    val isFlexibleAllowed: Boolean,
    val isImmediateAllowed: Boolean,
    val priority: Int,
    val isImmediateInProgress: Boolean,
    val isDownloaded: Boolean,
) {
    companion object {
        /** Play's documented range for `inAppUpdatePriority`. */
        const val MAX_PRIORITY: Int = 5

        /**
         * Folds a Play reading into this model, or null when there is nothing
         * to put in front of the user.
         *
         * FAIL SAFE, DELIBERATELY: null is the answer for every uncertain
         * reading, and [PlayAppUpdateSource] also returns null for every error
         * — so an install Play knows nothing about (a debug build, a
         * sideloaded APK, a device with no Play Store) behaves exactly as if
         * this feature were not there.
         *
         * @param availableVersionCode Play's `availableVersionCode`. A
         *   non-positive value is a reading we do not trust enough to throttle
         *   on, so it collapses to null.
         */
        fun fromPlay(
            updateState: PlayUpdateState,
            installState: PlayInstallState,
            availableVersionCode: Int,
            isFlexibleAllowed: Boolean,
            isImmediateAllowed: Boolean,
            priority: Int,
        ): AppUpdateAvailability? {
            if (availableVersionCode <= 0) return null

            // Clamped rather than trusted: priority drives the one blocking
            // path there is, so a value outside Play's documented 0..5 range
            // must not be able to escalate by accident.
            val safePriority = priority.coerceIn(0, MAX_PRIORITY)

            fun availability(
                immediateInProgress: Boolean = false,
                downloaded: Boolean = false,
            ) = AppUpdateAvailability(
                availableVersionCode = availableVersionCode,
                isFlexibleAllowed = isFlexibleAllowed,
                isImmediateAllowed = isImmediateAllowed,
                priority = safePriority,
                isImmediateInProgress = immediateInProgress,
                isDownloaded = downloaded,
            )

            // A finished download outranks everything: the bytes are already
            // on the device because the user asked for them, so the only
            // sensible next step is the restart that installs them. Checked
            // before updateState because Play reports IN_PROGRESS here too.
            if (installState == PlayInstallState.DOWNLOADED) {
                return availability(downloaded = true)
            }

            // Something is mid-flight. Prompting again would either duplicate
            // Play's own progress UI or restart a download in progress.
            if (installState == PlayInstallState.WORKING) return null

            return when (updateState) {
                PlayUpdateState.NOTHING -> null
                PlayUpdateState.AVAILABLE -> availability()
                // A blocking flow that was interrupted (backgrounded, killed)
                // has to be resumed — Play's contract — but only if the
                // blocking flow is still permitted. Otherwise there is nothing
                // actionable and we stay quiet.
                PlayUpdateState.IN_PROGRESS ->
                    if (isImmediateAllowed) availability(immediateInProgress = true) else null
            }
        }
    }
}
