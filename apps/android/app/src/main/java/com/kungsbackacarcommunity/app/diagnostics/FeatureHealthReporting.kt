package com.kungsbackacarcommunity.app.diagnostics

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.BuildConfig

/**
 * The wiring that turns a [FeatureHealthGate] decision into an actual report.
 *
 * Thin on purpose: all the rules live in the pure [FeatureHealthGate] /
 * [MapRenderWatchdog], and this just supplies the live conditions and forwards a
 * [FeatureHealthDecision.Report] to the existing [ClientErrorReporter] seam —
 * the same transport that already dedups into one public GitHub issue per
 * fingerprint.
 *
 * Never throws and never blocks: reporting that a feature is broken must not
 * itself break anything.
 */
class FeatureHealthReporter(
    private val gate: FeatureHealthGate,
    private val errorReporter: ClientErrorReporter?,
    private val networkStatus: NetworkStatus,
) {
    /** Live connectivity, for callers gating a watchdog tick on it. */
    fun isOnline(): Boolean = runCatching { networkStatus.isOnline() }.getOrDefault(false)

    /**
     * Contemplate reporting [kind]. Returns the decision (so callers and tests
     * can observe suppression) and files the report only when the gate allows.
     *
     * @param foreground whether the app is actually in front of the user.
     * @param surfaceShown whether the failing surface was actually displayed.
     */
    fun report(
        kind: FeatureHealthKind,
        foreground: Boolean,
        surfaceShown: Boolean,
    ): FeatureHealthDecision {
        val decision =
            runCatching {
                gate.decide(
                    kind = kind,
                    conditions =
                        FeatureHealthConditions(
                            online = isOnline(),
                            foreground = foreground,
                            surfaceShown = surfaceShown,
                        ),
                )
            }.getOrElse {
                return FeatureHealthDecision.Suppress(FeatureHealthSuppression.Offline)
            }
        if (decision is FeatureHealthDecision.Report) {
            runCatching {
                errorReporter?.report(
                    feature = decision.feature,
                    message = decision.message,
                    code = decision.code,
                )
            }
        }
        return decision
    }
}

/**
 * Process-scoped home of the [FeatureHealthGate].
 *
 * The once-per-session volume cap is only meaningful if the gate OUTLIVES the
 * composable that reports through it: the map surface is recreated on every
 * process-level config change, and a per-composition gate would file a fresh
 * issue each time. The backend dedups across users by fingerprint, but relying
 * on that alone would still burn the per-user callable rate budget and inflate
 * the occurrence tally into nonsense.
 */
object FeatureHealthSession {
    @Volatile
    private var cached: FeatureHealthGate? = null

    /** The process-wide gate, created once from [environment]. */
    @Synchronized
    fun gate(environment: FeatureHealthEnvironment): FeatureHealthGate =
        cached ?: FeatureHealthGate(environment).also { cached = it }

    /** Drop the cached gate so a test starts from a clean session. */
    @Synchronized
    fun resetForTests() {
        cached = null
    }
}

/**
 * Build the [FeatureHealthEnvironment] for this process.
 *
 * @param accessTokenPresent whether a Mapbox access token is configured. A
 *   BOOLEAN — the token value must never be passed here or anywhere near a
 *   report; the GitHub issue is world-readable.
 */
fun featureHealthEnvironment(accessTokenPresent: Boolean): FeatureHealthEnvironment =
    FeatureHealthEnvironment(
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        navSdkEnabled = BuildConfig.NAV_SDK_ENABLED,
        androidApiLevel = Build.VERSION.SDK_INT,
        mapboxMapsSdkVersion = BuildConfig.MAPBOX_MAPS_SDK_VERSION,
        accessTokenPresent = accessTokenPresent,
    )

/**
 * Remember the [FeatureHealthReporter] for a surface.
 *
 * A null underlying [ClientErrorReporter] (a config-less / CI build with no
 * Firebase) simply means reports go nowhere — the gate still runs, so the
 * decision logic behaves identically in tests and on device.
 */
@Composable
fun rememberFeatureHealthReporter(accessTokenPresent: Boolean): FeatureHealthReporter {
    val context = LocalContext.current
    return remember(context, accessTokenPresent) {
        FeatureHealthReporter(
            gate = FeatureHealthSession.gate(featureHealthEnvironment(accessTokenPresent)),
            errorReporter = FirebaseClientErrorReporter.createIfAvailable(context),
            networkStatus = ConnectivityNetworkStatus(context),
        )
    }
}
