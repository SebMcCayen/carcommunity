package com.kungsbackacarcommunity.app.diagnostics

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kungsbackacarcommunity.app.BuildConfig

/**
 * [CrashTelemetry] backed by Firebase Crashlytics.
 *
 * Guarded like every other Firebase seam in this app ([createIfAvailable]
 * returns null when `google-services.json` is absent, i.e. CI / local validation
 * builds), and totally failure-tolerant: every SDK call is wrapped, because
 * telemetry must never be the thing that crashes a screen.
 *
 * This class does NOT install an uncaught-exception handler — the Crashlytics
 * SDK does that itself when `FirebaseApp` initializes, before
 * [CrashReporter.install] chains the diagnostics reporter in front of it. See
 * [CrashTelemetry] for how the two coexist.
 */
class FirebaseCrashTelemetry private constructor(
    private val crashlytics: FirebaseCrashlytics,
) : CrashTelemetry {

    override fun setKey(key: String, value: String) {
        try {
            crashlytics.setCustomKey(CrashTelemetryText.value(key), CrashTelemetryText.value(value))
        } catch (_: Throwable) {
            // Telemetry must never surface a second failure to the caller.
        }
    }

    override fun log(event: String, detail: String?) {
        try {
            crashlytics.log(CrashTelemetryText.breadcrumb(event, detail))
        } catch (_: Throwable) {
            // As above.
        }
    }

    override fun recordNonFatal(feature: String, throwable: Throwable) {
        try {
            // Breadcrumb FIRST so the feature is visible in the log of the next
            // FATAL too — a swallowed error is often the step before the crash.
            crashlytics.log(CrashTelemetryText.breadcrumb(CrashEvents.NON_FATAL, feature))
            crashlytics.setCustomKey(CrashKeys.LAST_NON_FATAL, CrashTelemetryText.value(feature))
            crashlytics.recordException(throwable)
        } catch (_: Throwable) {
            // As above.
        }
    }

    companion object {
        /**
         * @return a live telemetry sink, or null when Firebase is not configured
         *   (guarded exactly like [FirebaseClientErrorReporter]; see
         *   [rememberCrashTelemetry] for the two ways callers absorb the null).
         */
        fun createIfAvailable(context: Context): CrashTelemetry? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return try {
                FirebaseCrashTelemetry(FirebaseCrashlytics.getInstance())
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * One-time process setup, called from `KccApplication#onCreate` right
         * after `FirebaseApp.initializeApp` and BEFORE [CrashReporter.install]:
         *
         * 1. Applies [CrashTelemetryPolicy.collectionEnabled] explicitly. The
         *    manifest already carries the same value per build type; this is the
         *    readable, unit-tested statement of the same decision.
         * 2. Attaches the static custom keys (build + feature-flag state) so the
         *    very first crash of the process already carries them.
         * 3. Touching `FirebaseCrashlytics.getInstance()` here also pins the
         *    ORDER of the two crash handlers: the Crashlytics handler is in
         *    place before [CrashReporter.install] chains itself in front of it,
         *    so a crash runs the diagnostics report and then the Crashlytics
         *    record, and neither masks the other.
         *
         * @return the telemetry sink, or null when Firebase is unavailable.
         */
        fun install(context: Context, isDebugBuild: Boolean = BuildConfig.DEBUG): CrashTelemetry? {
            val telemetry = createIfAvailable(context) ?: return null
            return try {
                FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled =
                    CrashTelemetryPolicy.collectionEnabled(isDebugBuild)
                telemetry.apply {
                    setKey(CrashKeys.BUILD_TYPE, if (isDebugBuild) "debug" else "release")
                    setKey(CrashKeys.VERSION_NAME, BuildConfig.VERSION_NAME)
                    setKey(CrashKeys.VERSION_CODE, BuildConfig.VERSION_CODE.toString())
                    setKey(CrashKeys.NAV_SDK_ENABLED, BuildConfig.NAV_SDK_ENABLED.toString())
                    setKey(CrashKeys.MAPBOX_SDK_VERSION, BuildConfig.MAPBOX_MAPS_SDK_VERSION)
                    setKey(CrashKeys.SHELL_TAB, CrashKeys.NONE)
                    setKey(CrashKeys.SHELL_ROUTE, CrashKeys.NONE)
                    setKey(CrashKeys.LIVE_SHARING, false.toString())
                    log(CrashEvents.APP_START)
                }
            } catch (_: Throwable) {
                telemetry
            }
        }
    }
}

/**
 * Composable accessor for [CrashTelemetry], mirroring
 * [rememberClientErrorReporter]. Returns null when Firebase is not configured
 * (CI / local validation builds without `google-services.json`).
 *
 * Both call-site shapes are in use, and which one applies is decided by the
 * consumer, not by preference:
 *
 * - **`?.` where the call is optional** — the telemetry call is the whole
 *   statement, so a null sink simply means it does not happen. This is what
 *   `AuthenticatedApp`'s navigation breadcrumb does (`crashTelemetry?.run { … }`)
 *   and what `LocationSharingService` does for its start/stop keys.
 * - **`?: NoopCrashTelemetry` where a non-null instance must be injected** — the
 *   pure controllers ([com.kungsbackacarcommunity.app.live.NearbyLiveController],
 *   [com.kungsbackacarcommunity.app.dm.DmThreadCoordinator],
 *   [com.kungsbackacarcommunity.app.chatchannels.ChannelChatCoordinator],
 *   [com.kungsbackacarcommunity.app.navigation.NavigationController]) take a
 *   non-null `CrashTelemetry` by constructor so their own bodies stay free of
 *   null checks, so the composable that builds them substitutes the no-op.
 *
 * Neither is a "fallback path" in the sense of alternative behaviour: both mean
 * *drop the telemetry, change nothing else*. What must never appear is a null
 * check that alters what the app does.
 */
@Composable
fun rememberCrashTelemetry(): CrashTelemetry? {
    val context = LocalContext.current
    return remember(context) { FirebaseCrashTelemetry.createIfAvailable(context) }
}
