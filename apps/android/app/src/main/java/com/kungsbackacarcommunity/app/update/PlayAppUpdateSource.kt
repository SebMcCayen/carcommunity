package com.kungsbackacarcommunity.app.update

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.kungsbackacarcommunity.app.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [AppUpdateSource] backed by Google Play's In-App Updates API
 * (`AppUpdateManager`).
 *
 * This is the whole automatic-update mechanism: Play is asked, at the moment
 * the app starts, whether a newer build is live on the track this install came
 * from. Nothing is maintained by hand, there is no server document, and there
 * is no operator step at release time — publish to Play and the prompt starts
 * appearing on its own, for exactly the users Play would actually serve.
 *
 * NON-PLAY INSTALLS, WHICH IS MOST OF DEVELOPMENT: an APK put on a device by
 * `adb install`, a sideloaded build, or a device with no Play Store at all has
 * no Play install context. `AppUpdateManagerFactory.create` may work but the
 * info request then fails with an `InstallException`
 * (`ERROR_APP_NOT_OWNED` / `ERROR_API_NOT_AVAILABLE`). EVERY such failure is
 * swallowed here and reported as null — "nothing to offer". The member never
 * sees an error, nothing crashes, and the app behaves as if the feature did
 * not exist. That is also why the check is left enabled in debug builds: the
 * degradation path is the one that runs most often, so it should be the one
 * that gets exercised.
 */
class PlayAppUpdateSource private constructor(
    private val manager: AppUpdateManager,
) : AppUpdateSource {

    /**
     * Play's flow needs the very `AppUpdateInfo` object the reading came from —
     * a reconstructed one will not do — so the last successful fetch is kept.
     * Atomic because the fetch runs off the main thread and the flow starts on
     * it.
     */
    private val lastInfo = AtomicReference<AppUpdateInfo?>(null)

    override suspend fun fetch(): AppUpdateAvailability? {
        val info = requestInfo() ?: return null
        lastInfo.set(info)
        return runQuietly("read") {
            AppUpdateAvailability.fromPlay(
                updateState = updateState(info.updateAvailability()),
                installState = installState(info.installStatus()),
                availableVersionCode = info.availableVersionCode(),
                isFlexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                isImmediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                priority = info.updatePriority(),
            )
        }
    }

    override fun startFlow(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        immediate: Boolean,
    ): Boolean {
        val info = lastInfo.get() ?: return false
        val type = if (immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        // Guarded because this reaches across a process boundary into Play:
        // a SendIntentException, a Play app that was disabled since the fetch,
        // or a stale info object must produce "could not start", never a crash.
        return runQuietly("startFlow") {
            manager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(type).build(),
            )
        } ?: false
    }

    override fun onDownloadComplete(onDownloaded: () -> Unit): () -> Unit {
        val listener = InstallStateUpdatedListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) onDownloaded()
        }
        val registered = runQuietly("registerListener") { manager.registerListener(listener) }
        // A registration that never happened must still hand back a no-op
        // unregister, so the caller's cleanup path needs no special case.
        if (registered == null) return {}
        return { runQuietly("unregisterListener") { manager.unregisterListener(listener) } }
    }

    override fun completeUpdate(): Boolean =
        runQuietly("completeUpdate") { manager.completeUpdate() } != null

    /**
     * The `Task<AppUpdateInfo>` as a suspend call. Resolves to null on ANY
     * failure — see the class comment; this is where a non-Play install lands.
     */
    private suspend fun requestInfo(): AppUpdateInfo? {
        val task = runQuietly("appUpdateInfo") { manager.appUpdateInfo } ?: return null
        return suspendCancellableCoroutine { continuation ->
            task.addOnCompleteListener { completed ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (completed.isSuccessful) {
                    continuation.resume(completed.result)
                } else {
                    // NOT resumeWithException: an InstallException here is the
                    // ordinary reading on a debug or sideloaded build, not an
                    // error anyone needs to hear about.
                    breadcrumb("appUpdateInfo", completed.exception)
                    continuation.resume(null)
                }
            }
        }
    }

    /**
     * Runs [block], turning any failure into null. Debug builds get a
     * `Log.d` breadcrumb; release builds are silent. Coroutine cancellation is
     * deliberately NOT caught — [block] never suspends, so the only
     * CancellationException that could arrive is a real one.
     */
    private inline fun <T> runQuietly(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            breadcrumb(what, e)
            null
        }

    private fun breadcrumb(what: String, cause: Throwable?) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Play in-app update: $what unavailable (${cause?.javaClass?.simpleName})")
        }
    }

    companion object {
        private const val TAG = "AppUpdate"

        /**
         * Creates the source, or null when the Play update API cannot be
         * constructed at all on this device.
         *
         * Follows the pattern this app already uses for "an SDK that may not
         * be there" (see `FirebaseFeatureFlagsRepository`): null propagates
         * all the way to no prompt.
         */
        fun createIfAvailable(context: Context): AppUpdateSource? =
            try {
                PlayAppUpdateSource(AppUpdateManagerFactory.create(context.applicationContext))
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Play in-app update unavailable (${e.javaClass.simpleName})")
                }
                null
            }

        private fun updateState(availability: Int): PlayUpdateState =
            when (availability) {
                UpdateAvailability.UPDATE_AVAILABLE -> PlayUpdateState.AVAILABLE
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ->
                    PlayUpdateState.IN_PROGRESS
                // UPDATE_NOT_AVAILABLE and UNKNOWN both mean "say nothing".
                else -> PlayUpdateState.NOTHING
            }

        private fun installState(status: Int): PlayInstallState =
            when (status) {
                InstallStatus.DOWNLOADED -> PlayInstallState.DOWNLOADED
                InstallStatus.PENDING,
                InstallStatus.DOWNLOADING,
                InstallStatus.INSTALLING,
                -> PlayInstallState.WORKING
                // UNKNOWN / INSTALLED / FAILED / CANCELED: nothing in flight.
                else -> PlayInstallState.IDLE
            }
    }
}
