package com.kungsbackacarcommunity.app.diagnostics

/**
 * Feature HEALTH ASSERTIONS — the "it didn't crash, it just silently stopped
 * working" detector.
 *
 * ## Why this exists
 *
 * Release v0.8.1 enabled the Mapbox Navigation SDK. Turn-by-turn worked, but the
 * map stopped rendering entirely: a blank rectangle where the basemap used to
 * be. Nothing crashed, nothing threw, no `catch` block anywhere in the app ran —
 * so [CrashReporter] saw nothing and no [ClientErrorReporter] call site fired.
 * The app's whole error pipeline is driven by *thrown* failures, and this class
 * of defect never throws.
 *
 * This file adds the missing half: positive assertions that a feature is
 * actually WORKING, reported through the existing [ClientErrorReporter] seam
 * (which already dedups into one public GitHub issue per fingerprint).
 *
 * ## The two detection strategies, and why both are needed
 *
 * 1. **Listener-based** ([FeatureHealthKind.MapStyleLoadFailed],
 *    [FeatureHealthKind.MapResourceLoadError]): the Maps SDK told us it failed.
 *    Precise, but only fires when the SDK NOTICES. The v0.8.1 bug may well have
 *    produced no callback at all.
 * 2. **Watchdog** ([MapRenderWatchdog] → [FeatureHealthKind.MapRenderTimeout]):
 *    the map never reached a rendered state, and nobody told us why. This is the
 *    check that catches a silent failure, and the one that would actually have
 *    caught v0.8.1.
 *
 * ## PII: the payload is PUBLIC
 *
 * A report here becomes a **world-readable GitHub issue**. Everything this file
 * puts on the wire is build/device metadata that is identical for every user on
 * the same build: error kind, app version, Maps SDK version, `NAV_SDK_ENABLED`,
 * Android API level, and a BOOLEAN [FeatureHealthEnvironment.accessTokenPresent]
 * — never the token itself, not even truncated. No uid, no display name, no
 * coordinates, no destination, no search query, no exception message (an
 * exception message is the classic carrier of a Firestore doc path containing a
 * uid, so this file never ships one).
 *
 * ## Consent
 *
 * The app has NO diagnostics/telemetry opt-out to honour — there is no such
 * setting anywhere in the app, and the existing [CrashReporter] and
 * [ClientErrorReporter] paths are likewise unconditional. This file deliberately
 * does not invent one; if an opt-out is ever added, [FeatureHealthGate.decide]
 * is the single choke point where it must be enforced.
 */

/**
 * A distinct, stable class of silent failure.
 *
 * [feature] is the dot-path that becomes the GitHub issue TITLE; [codePrefix] is
 * the stable status token that (with the app version) becomes the dedup
 * fingerprint. Both are compile-time constants on purpose: the backend
 * fingerprint is `sha256(feature | code)`, so anything varying per user or per
 * device here would fragment one defect into thousands of issues.
 */
enum class FeatureHealthKind(
    val feature: String,
    val codePrefix: String,
    val summary: String,
) {
    /**
     * The Maps SDK reported a STYLE load failure. The style is the whole
     * basemap — this is unambiguously a blank/broken map, never a benign
     * viewport-scroll miss.
     */
    MapStyleLoadFailed(
        feature = "mapHealth.styleLoad",
        codePrefix = "MAP_STYLE_LOAD_FAILED",
        summary = "Mapbox style failed to load",
    ),

    /**
     * The Maps SDK reported a SPRITE or GLYPHS load failure — the icon atlas or
     * the font atlas. Both are whole-map resources rather than per-viewport
     * ones, so a failure means broken rendering everywhere, not "the user
     * scrolled past a tile that hadn't downloaded".
     *
     * Deliberately EXCLUDES `SOURCE` and `TILE` errors: those fire routinely and
     * benignly whenever the network is flaky or the user pans somewhere not yet
     * cached, and reporting them would bury the real defects in noise.
     */
    MapResourceLoadError(
        feature = "mapHealth.mapLoad",
        codePrefix = "MAP_RESOURCE_LOAD_ERROR",
        summary = "Mapbox sprite/glyph resource failed to load",
    ),

    /**
     * THE v0.8.1 CHECK. The map surface was shown, in the foreground, with
     * working connectivity, for [MAP_RENDER_TIMEOUT_MILLIS] of eligible time —
     * and never rendered a full frame, and the SDK never said why.
     */
    MapRenderTimeout(
        feature = "mapHealth.renderTimeout",
        codePrefix = "MAP_RENDER_TIMEOUT",
        summary = "Map surface never rendered a full frame",
    ),

    /** `NAV_SDK_ENABLED` is true but the navigation session failed to initialise. */
    NavSessionInitFailed(
        feature = "navHealth.sessionInit",
        codePrefix = "NAV_SESSION_INIT_FAILED",
        summary = "Navigation session failed to initialise",
    ),

    /**
     * Every bounded route-request attempt failed, leaving turn-by-turn on a
     * silent dead end (map, no route, no error). Reported only after the retry
     * budget is exhausted so a single transient failure stays quiet.
     */
    NavRouteRequestFailed(
        feature = "navHealth.routeRequest",
        codePrefix = "NAV_ROUTE_REQUEST_FAILED",
        summary = "Navigation route request failed after all retries",
    ),
    ;

    /** True for kinds that are meaningless when the Nav SDK is not in the build. */
    val requiresNavSdk: Boolean
        get() = this == NavSessionInitFailed || this == NavRouteRequestFailed
}

/**
 * Build/device facts attached to every report. Fixed for the whole process.
 *
 * Every field here is safe to publish: it is either a build constant or a coarse
 * device fact shared by millions of handsets. [accessTokenPresent] is a BOOLEAN
 * — the token value must never appear in this type.
 */
data class FeatureHealthEnvironment(
    val appVersionName: String,
    val appVersionCode: Long,
    val navSdkEnabled: Boolean,
    val androidApiLevel: Int,
    val mapboxMapsSdkVersion: String,
    val accessTokenPresent: Boolean,
)

/**
 * The live conditions at the moment a report is contemplated. Every one of these
 * is a suppression gate — see [FeatureHealthGate.decide].
 */
data class FeatureHealthConditions(
    /**
     * Whether the device has VALIDATED internet. A user in a tunnel, on a plane,
     * or out of data will fail to load tiles; that is the network working as
     * designed, not a defect, and must never file an issue.
     */
    val online: Boolean,
    /** Whether the app is actually in the foreground (a backgrounded map is not a broken map). */
    val foreground: Boolean,
    /** Whether the surface was actually shown to the user (never-composed is not broken). */
    val surfaceShown: Boolean,
)

/** Why a contemplated report was not filed. Exposed so tests can assert the reason. */
enum class FeatureHealthSuppression {
    /** No connectivity — a tile/style load failure here is expected, not a defect. */
    Offline,

    /** App is backgrounded; a map nobody is looking at rendering late is not a bug. */
    Backgrounded,

    /** The surface never actually appeared, so there is nothing to assert about it. */
    SurfaceNeverShown,

    /** A nav-only kind on a build without the Nav SDK. */
    NavSdkDisabled,

    /** Already filed this kind once in this process — the per-session volume cap. */
    AlreadyReportedThisSession,
}

/** The outcome of [FeatureHealthGate.decide]. */
sealed interface FeatureHealthDecision {
    /** File it. Fields are exactly the [ClientErrorReporter.report] arguments. */
    data class Report(
        val feature: String,
        val message: String,
        val code: String,
    ) : FeatureHealthDecision

    /** Do not file it, for [reason]. */
    data class Suppress(val reason: FeatureHealthSuppression) : FeatureHealthDecision
}

/**
 * The single decision point for "should this health failure become a public
 * GitHub issue?" — deliberately pure and side-effect-free so the rules that make
 * this feature harmful when wrong (false positives, spam) are unit-testable
 * rather than buried in a composable.
 *
 * ## Fingerprint
 *
 * The backend computes `sha256(feature | code.uppercase())` — the MESSAGE does
 * not participate whenever a code is present. So the fingerprint here is exactly:
 *
 *     feature (e.g. "mapHealth.renderTimeout")  +  "<CODE_PREFIX>@<appVersionName>"
 *
 * - Stable across every user and every device for the same defect on the same
 *   build, so a fleet-wide outage collapses to ONE issue with an occurrence
 *   tally.
 * - Scoped BY APP VERSION on purpose. Without the version, a defect fixed in one
 *   release and regressed in a later one would silently bump the counter on the
 *   already-closed issue and nobody would ever see it — which is precisely the
 *   "we shipped it and didn't notice" failure this whole feature exists to
 *   prevent. Version-scoping means a regression files a fresh issue.
 * - Not so coarse that distinct defects merge: each [FeatureHealthKind] carries
 *   its own feature AND code, so a style-load failure and a render timeout are
 *   always separate issues.
 *
 * Everything varying per device (model, OS version) is carried in the report's
 * own metadata fields, which are outside the fingerprint by design.
 */
class FeatureHealthGate(private val environment: FeatureHealthEnvironment) {
    private val reported = mutableSetOf<FeatureHealthKind>()

    /**
     * Decide whether [kind] should be reported under [conditions].
     *
     * Suppression is checked BEFORE the once-per-session cap is consumed, so a
     * failure that happens while offline or backgrounded does not burn the
     * single report slot for that kind — the same failure can still be reported
     * later under conditions where it actually means something.
     */
    @Synchronized
    fun decide(kind: FeatureHealthKind, conditions: FeatureHealthConditions): FeatureHealthDecision {
        if (kind.requiresNavSdk && !environment.navSdkEnabled) {
            return FeatureHealthDecision.Suppress(FeatureHealthSuppression.NavSdkDisabled)
        }
        if (!conditions.surfaceShown) {
            return FeatureHealthDecision.Suppress(FeatureHealthSuppression.SurfaceNeverShown)
        }
        if (!conditions.foreground) {
            return FeatureHealthDecision.Suppress(FeatureHealthSuppression.Backgrounded)
        }
        if (!conditions.online) {
            return FeatureHealthDecision.Suppress(FeatureHealthSuppression.Offline)
        }
        if (!reported.add(kind)) {
            return FeatureHealthDecision.Suppress(FeatureHealthSuppression.AlreadyReportedThisSession)
        }
        return FeatureHealthDecision.Report(
            feature = kind.feature,
            message = buildMessage(kind),
            code = buildCode(kind),
        )
    }

    /** Whether [kind] has already been reported in this process. Test/diagnostic hook. */
    @Synchronized
    fun hasReported(kind: FeatureHealthKind): Boolean = kind in reported

    private fun buildCode(kind: FeatureHealthKind): String =
        "${kind.codePrefix}@${sanitizeVersion(environment.appVersionName)}"

    /**
     * Human context for the issue body. NOT part of the fingerprint, but still
     * strictly build/device metadata — see the file KDoc's PII rules.
     */
    private fun buildMessage(kind: FeatureHealthKind): String =
        buildString {
            append(kind.summary)
            append(" | maps=").append(environment.mapboxMapsSdkVersion)
            append(" | navSdk=").append(environment.navSdkEnabled)
            append(" | api=").append(environment.androidApiLevel)
            append(" | build=").append(environment.appVersionCode)
            append(" | tokenPresent=").append(environment.accessTokenPresent)
        }

    private companion object {
        /**
         * Keep a version string to characters that survive the backend's
         * fingerprint normalisation unchanged, and bound its length, so a weird
         * versionName can never fragment or bloat the dedup key.
         */
        fun sanitizeVersion(raw: String): String {
            val kept = raw.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            return when {
                kept.isEmpty() -> "unknown"
                kept.length > MAX_VERSION_CHARS -> kept.take(MAX_VERSION_CHARS)
                else -> kept
            }
        }

        const val MAX_VERSION_CHARS = 24
    }
}

/**
 * How long the map may go without rendering a full frame before we call it
 * broken.
 *
 * 12 seconds. A cold Mapbox style + first-tile load completes in well under 5s
 * on a healthy connection and typically under 8s on a slow one, so 12s clears
 * the realistic worst case with headroom — while still being short enough to
 * land inside a normal session rather than after the user has given up and left.
 *
 * Critically, this budget counts only ELIGIBLE time (see [MapRenderWatchdog]):
 * seconds spent offline, backgrounded, or with the map covered do not count
 * toward it, so 12s means 12 seconds of "the map had every reason to work".
 */
const val MAP_RENDER_TIMEOUT_MILLIS: Long = 12_000L

/**
 * Accumulating watchdog for "the map never rendered and nothing told us why".
 *
 * Pure and clock-free: the caller supplies elapsed time, so the whole
 * false-positive surface is unit-testable without a device or a real timer.
 *
 * The accumulate-only-while-eligible design is what keeps this quiet. A flat
 * `delay(12s)` would fire for the user who opened the app in a tunnel, or
 * backgrounded it, or was sitting on another tab — all of which are the app
 * working correctly. Here, those seconds simply never accrue, and the moment the
 * map does render the watchdog is disarmed permanently.
 */
class MapRenderWatchdog(private val timeoutMillis: Long = MAP_RENDER_TIMEOUT_MILLIS) {
    private var eligibleElapsedMillis = 0L
    private var disarmed = false

    /** Eligible time accumulated so far. Test/diagnostic hook. */
    val elapsedMillis: Long
        get() = eligibleElapsedMillis

    /** True once the watchdog can never fire again (it fired, or the map rendered). */
    val isDisarmed: Boolean
        get() = disarmed

    /**
     * Advance by [tickMillis].
     *
     * @param eligible true only when the map was shown, visible, in the
     *   foreground, and online for this tick — i.e. when a non-rendering map is
     *   genuinely anomalous.
     * @param rendered true once the map has rendered a full frame. Disarms the
     *   watchdog permanently: a map that rendered and later went blank is a
     *   different defect, and firing a render-TIMEOUT for it would be wrong.
     * @return true exactly once, on the tick the budget is exhausted.
     */
    fun onTick(tickMillis: Long, eligible: Boolean, rendered: Boolean): Boolean {
        if (disarmed) return false
        if (rendered) {
            disarmed = true
            return false
        }
        if (!eligible || tickMillis <= 0L) return false
        eligibleElapsedMillis += tickMillis
        if (eligibleElapsedMillis < timeoutMillis) return false
        disarmed = true
        return true
    }
}

/**
 * Map a Maps SDK `MapLoadingErrorType` name to the health kind it warrants, or
 * null when the error is too benign/noisy to report.
 *
 * Takes the enum NAME rather than the SDK type so the decision stays pure and
 * testable without the Mapbox SDK on the unit-test runtime classpath.
 *
 * `SOURCE` and `TILE` are intentionally unmapped: they fire routinely as the
 * user pans to uncached areas or the connection wobbles, and a map missing one
 * tile is not a broken map. `STYLE`, `SPRITE` and `GLYPHS` are whole-map
 * resources — losing any of them means the map is broken everywhere.
 */
fun mapLoadingErrorKindFor(typeName: String): FeatureHealthKind? =
    when (typeName) {
        "STYLE" -> FeatureHealthKind.MapStyleLoadFailed
        "SPRITE", "GLYPHS" -> FeatureHealthKind.MapResourceLoadError
        else -> null
    }
