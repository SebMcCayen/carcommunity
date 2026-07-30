package com.kungsbackacarcommunity.app.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/**
 * Asks Google Play whether a newer build is available, and runs Play's update
 * flow. A Play-free boundary so the shell wiring and the policy can be
 * exercised against a fake.
 *
 * WHY PLAY AND NOT A SERVER VALUE: Play answers for the track the install
 * actually came from, at the moment it is asked. A number written somewhere by
 * a release pipeline can only say "a build with this code was produced" — it
 * cannot know whether Play has finished reviewing it, whether the rollout has
 * reached this device's percentage, or whether this user is even on that
 * track. Announcing an update Play will not serve produces a prompt that
 * cannot be satisfied and therefore keeps returning, which is worse than no
 * prompt at all.
 */
interface AppUpdateSource {

    /** The current reading, or null when there is nothing to offer. */
    suspend fun fetch(): AppUpdateAvailability?

    /**
     * Hands off to Play's own update flow for the reading last returned by
     * [fetch].
     *
     * @param immediate true for the blocking full-screen flow, false for the
     *   background (flexible) one.
     * @return false when the flow could not be started at all, so the caller
     *   can fall back to the Play listing rather than leaving a dead button.
     */
    fun startFlow(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        immediate: Boolean,
    ): Boolean

    /**
     * Calls [onDownloaded] when a flexible update finishes downloading during
     * this session.
     *
     * @return the unregister action. Always safe to call, even if nothing was
     *   registered.
     */
    fun onDownloadComplete(onDownloaded: () -> Unit): () -> Unit

    /**
     * Restarts into a downloaded update.
     *
     * @return false when the restart could not be triggered.
     */
    fun completeUpdate(): Boolean
}
