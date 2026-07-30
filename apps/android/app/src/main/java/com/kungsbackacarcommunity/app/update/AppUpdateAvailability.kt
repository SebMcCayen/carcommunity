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
 * @property isImmediateInProgress a BLOCKING flow was started and has not
 *   finished — Play still reports it as in progress, whatever its install
 *   status (pending, downloading, installing, or reset to unknown by a process
 *   death). True only when the blocking flow is the one that owns it; an
 *   ordinary flexible download in flight reads as in progress to Play too, and
 *   must not be turned into a full-screen takeover.
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

            // Play's two readings are INDEPENDENT AXES, not a sequence.
            // `updateAvailability()` says whether an update THIS APP started is
            // running; `installStatus()` says how far its download has got. An
            // update in flight therefore reports
            // DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS *together with*
            // PENDING/DOWNLOADING/INSTALLING — which is precisely the state a
            // blocking flow has to be resumed from. So the fold is one
            // exhaustive `when` over the whole matrix, with an explicit answer
            // for every combination: reading one axis and returning early on
            // the other is what previously made the resume unreachable.

            // Whether the BLOCKING flow is the one that owns an in-flight
            // update. It has to be asked, because Play reports a perfectly
            // ordinary flexible background download as IN_PROGRESS too, and
            // resuming that as a full-screen takeover is exactly what choosing
            // the flexible flow exists to avoid. This app starts IMMEDIATE in
            // one situation only — a release published at the top of Play's
            // priority scale, with the blocking flow permitted (see
            // [AppUpdatePolicy]) — so those same two facts identify a blocking
            // flow, and nothing else, as the thing to resume.
            val resumesBlockingFlow = isImmediateAllowed && safePriority >= MAX_PRIORITY

            fun resumeOrNothing() =
                if (resumesBlockingFlow) availability(immediateInProgress = true) else null

            return when (installState) {
                // A finished download outranks everything: the bytes are
                // already on the device because the user asked for them, so the
                // only sensible next step is the restart that installs them.
                // Read before updateState because Play keeps reporting
                // IN_PROGRESS alongside DOWNLOADED.
                PlayInstallState.DOWNLOADED -> availability(downloaded = true)

                PlayInstallState.WORKING ->
                    when (updateState) {
                        // Bytes moving with no update behind them is a reading
                        // that contradicts itself; either way, prompting now
                        // would duplicate Play's own progress UI or restart a
                        // download that is already running.
                        PlayUpdateState.NOTHING, PlayUpdateState.AVAILABLE -> null
                        // The blocking flow's ordinary interrupted state: Play
                        // is still downloading or installing the update this
                        // app started, and its contract is that the app resumes
                        // it rather than leaving the member stranded.
                        PlayUpdateState.IN_PROGRESS -> resumeOrNothing()
                    }

                PlayInstallState.IDLE ->
                    when (updateState) {
                        PlayUpdateState.NOTHING -> null
                        PlayUpdateState.AVAILABLE -> availability()
                        // Same resume, reached after a process death: the
                        // install status resets to UNKNOWN while Play still
                        // reports the update as in progress.
                        PlayUpdateState.IN_PROGRESS -> resumeOrNothing()
                    }
            }
        }
    }
}
