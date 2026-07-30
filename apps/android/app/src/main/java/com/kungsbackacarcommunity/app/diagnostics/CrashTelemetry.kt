package com.kungsbackacarcommunity.app.diagnostics

/**
 * Crash TELEMETRY seam — the Crashlytics half of this app's crash reporting.
 *
 * ## Why this exists alongside [CrashReporter]
 *
 * The two are complementary and neither replaces the other:
 *
 * - [CrashReporter] + [DiagnosticsReport] is the home-grown, privacy-reviewed
 *   pipeline. It submits a heavily sanitized `critical` report through the
 *   public `diagnostics-submitReport` callable into `diagnosticsReports`, which
 *   the admin diagnostics view reads. By deliberate design it carries the
 *   exception CLASS and a masked one-line message and **never a stack trace**
 *   (see [DiagnosticsReports.fromThrowable]). It tells you *what* threw.
 * - Crashlytics, reached through this interface, carries the full stack trace,
 *   breadcrumbs, custom keys, grouping and the crash-free-users metric. It tells
 *   you *where* it threw and *how the user got there*.
 *
 * Uncaught exceptions reach BOTH: Crashlytics installs its own
 * `Thread.UncaughtExceptionHandler` when `FirebaseApp` initializes, and
 * [CrashReporter.install] then chains itself IN FRONT of it and delegates
 * onward, so one crash runs the diagnostics report and then the Crashlytics
 * record. Nothing here duplicates that — this interface exists for the things
 * an uncaught-exception hook cannot see: the context leading up to a crash, and
 * the errors the app SWALLOWS on purpose (which never reach any crash handler).
 *
 * ## The rules a call site must honour
 *
 * - **No PII. Ever.** Not in a key, not in a breadcrumb, not in a feature name.
 *   No uid, email, display name, coordinates, message or chat content, search
 *   text, vehicle registration, or any other user free-text. Keys and events are
 *   drawn from the fixed vocabularies in [CrashKeys], [CrashEvents] and
 *   [CrashFeatures]. If a value must be derived from user data, run it through
 *   [CrashTelemetryText.userDerived] first — but prefer not needing to.
 * - **Never break the UX.** Every implementation returns immediately and
 *   swallows its own failures; reporting must never become the thing that fails.
 * - **Be selective with breadcrumbs.** The buffer is bounded (~64 entries), so a
 *   breadcrumb per recomposition pushes out the ones that would have explained
 *   the crash. Log navigation and significant state transitions, nothing else.
 *
 * Firebase-free, exactly like [DiagnosticsReporter] and [ClientErrorReporter],
 * so pure logic can take one by constructor injection and unit tests never touch
 * the Firebase SDK ([NoopCrashTelemetry] is the default in every such
 * constructor).
 */
interface CrashTelemetry {
    /**
     * Attaches a custom key, shown on every subsequent crash report. Use a
     * constant from [CrashKeys]; values are app-generated, never user data.
     */
    fun setKey(key: String, value: String)

    /**
     * Appends a breadcrumb to the bounded log shown on the next crash report.
     * [event] is a constant from [CrashEvents]; [detail] is optional, short,
     * app-generated context (an enum name, a state name) — never user data.
     */
    fun log(event: String, detail: String? = null)

    /**
     * Records a NON-FATAL exception: an error the app caught and handled, which
     * therefore never reaches any uncaught-exception handler. Appears in the
     * Firebase console separately from fatals and does NOT count against the
     * crash-free-users metric.
     *
     * @param feature stable dot-path from [CrashFeatures], e.g.
     *   `live.nearbyRefresh`. It is the grouping handle, so keep it stable.
     */
    fun recordNonFatal(feature: String, throwable: Throwable)
}

/**
 * No-op telemetry: the default in every constructor that takes a
 * [CrashTelemetry], so unit tests and config-less builds need no Firebase.
 * Production call sites receive `null` from
 * [FirebaseCrashTelemetry.createIfAvailable] when Firebase is absent and use
 * `?.` — this object is for the injected-dependency case, matching
 * [NoopDiagnosticsReporter].
 */
object NoopCrashTelemetry : CrashTelemetry {
    override fun setKey(key: String, value: String) = Unit

    override fun log(event: String, detail: String?) = Unit

    override fun recordNonFatal(feature: String, throwable: Throwable) = Unit
}

/**
 * The FIXED custom-key vocabulary. Crashlytics allows 64 keys per report; this
 * list stays well inside that and every entry is app-generated — build metadata,
 * feature-flag state, and which screen the user was on. Nothing here can carry
 * user data.
 */
object CrashKeys {
    /** Debug or release. Should always read `release` in the console. */
    const val BUILD_TYPE = "build_type"

    /** `BuildConfig.VERSION_NAME`, e.g. `0.8.13`. */
    const val VERSION_NAME = "version_name"

    /** `BuildConfig.VERSION_CODE`, e.g. `24`. */
    const val VERSION_CODE = "version_code"

    /**
     * `BuildConfig.NAV_SDK_ENABLED` — the app's real build-time feature flag:
     * whether the Mapbox Navigation SDK (in-app turn-by-turn) is compiled in, or
     * the `src/noNav` hand-off stub is. Two different code paths ship under one
     * version name, so a crash is ambiguous without this.
     */
    const val NAV_SDK_ENABLED = "nav_sdk_enabled"

    /** `BuildConfig.MAPBOX_MAPS_SDK_VERSION` — the pinned Maps SDK. */
    const val MAPBOX_SDK_VERSION = "mapbox_sdk_version"

    /** The selected bottom-nav tab ([com.kungsbackacarcommunity.app.shell.ShellTab] name). */
    const val SHELL_TAB = "shell_tab"

    /**
     * The open full-screen sub-route
     * ([com.kungsbackacarcommunity.app.shell.ShellRoute] name), or [NONE].
     */
    const val SHELL_ROUTE = "shell_route"

    /** Whether the live-location foreground service is running (`true`/`false`). */
    const val LIVE_SHARING = "live_sharing"

    /** The [CrashFeatures] path of the most recent non-fatal, for triage. */
    const val LAST_NON_FATAL = "last_non_fatal"

    /** Value used for "no sub-route open" / "not set". */
    const val NONE = "none"
}

/**
 * The FIXED breadcrumb vocabulary. Deliberately short: navigation, the
 * live-location service lifecycle, and non-fatal markers. Anything more
 * frequent would evict the entries that actually explain a crash.
 */
object CrashEvents {
    /** Process start, logged once from `KccApplication#onCreate`. */
    const val APP_START = "app.start"

    /** A shell tab / sub-route change. Detail is `tab=<Tab> route=<Route>`. */
    const val NAV = "nav"

    /** The live-location foreground service started. */
    const val LIVE_SHARING_START = "live.sharingStart"

    /** The live-location foreground service was destroyed. */
    const val LIVE_SHARING_STOP = "live.sharingStop"

    /** Written by [CrashTelemetry.recordNonFatal] itself; detail is the feature. */
    const val NON_FATAL = "nonFatal"
}

/**
 * The FIXED non-fatal feature vocabulary — `area.action`, mirroring the
 * `feature` convention already used by [ClientErrorReporter]. Stable strings:
 * Crashlytics groups non-fatals by throwable, and this is what tells you which
 * swallowed path produced it.
 */
object CrashFeatures {
    /** `NearbyLiveController.refresh` — the map's nearby-sharer fetch failed. */
    const val LIVE_NEARBY_REFRESH = "live.nearbyRefresh"

    /** `LocationSharingService` — the own-session listener died for good. */
    const val LIVE_SESSION_LISTENER = "live.sessionListener"

    /** `NavigationController` — resolving the route origin threw. */
    const val NAV_ORIGIN = "navigation.origin"

    /** `DmThreadCoordinator.send` — an UNEXPECTED throw (not a mapped failure). */
    const val DM_SEND = "dm.send"

    /** `ChannelChatCoordinator.send` — an UNEXPECTED throw. */
    const val CHANNEL_SEND = "channel.send"
}

/**
 * Whether crash telemetry collects at all. Pure so the decision is unit-tested
 * rather than asserted in a comment.
 */
object CrashTelemetryPolicy {
    /**
     * Collection is ON for release builds and OFF for debug builds.
     *
     * A developer's crashes — including deliberately-triggered test crashes —
     * would otherwise land in the same dashboard as members' crashes and drag
     * down crash-free-users, the one number that has to stay trustworthy. The
     * same decision is expressed statically in the manifest
     * (`firebase_crashlytics_collection_enabled`, substituted per build type)
     * so it also holds for a crash that beats `Application#onCreate`.
     *
     * There is deliberately NO consent gate here; see docs/crashlytics.md for
     * why (the app's consent surfaces cover account terms and an anonymised
     * partner-statistics opt-in, neither of which governs crash diagnostics —
     * and the existing `diagnostics-submitReport` crash pipeline is likewise
     * ungated).
     */
    fun collectionEnabled(isDebugBuild: Boolean): Boolean = !isDebugBuild
}

/**
 * Bounding + sanitization for everything handed to Crashlytics. Pure, so the
 * PII stance is unit-tested rather than trusted.
 */
object CrashTelemetryText {
    /** Crashlytics truncates keys, values and log lines beyond 1024 chars. */
    const val MAX_LENGTH = 1024

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Normalizes a key/value/event to a single bounded line. Call sites pass
     * constants, so this is a guard rail rather than a transformation: it only
     * collapses whitespace (a newline in a Crashlytics value corrupts the
     * rendered report) and enforces the length bound.
     */
    fun value(raw: String): String =
        WHITESPACE.replace(raw, " ").trim().take(MAX_LENGTH)

    /**
     * The ONLY sanctioned way to put anything derived from user data into a key
     * or breadcrumb. Applies the SAME masking the privacy-reviewed diagnostics
     * pipeline applies to a throwable message — emails, UUIDs, unix paths and
     * digit runs all masked ([DiagnosticsReports.sanitizeMessage]) — then bounds
     * it. Prefer not needing this at all; a constant is always safer.
     */
    fun userDerived(raw: String): String =
        DiagnosticsReports.sanitizeMessage(raw).take(MAX_LENGTH)

    /**
     * Renders a breadcrumb line: `event` on its own, or `event: detail`. Blank
     * details are dropped rather than rendered as a dangling colon.
     */
    fun breadcrumb(event: String, detail: String?): String {
        val safeEvent = value(event)
        val safeDetail = detail?.let { value(it) }?.takeIf { it.isNotEmpty() }
        return if (safeDetail == null) safeEvent else "$safeEvent: $safeDetail".take(MAX_LENGTH)
    }

    /**
     * The [CrashEvents.NAV] breadcrumb detail. Both arguments are enum names
     * from the shell's fixed route vocabulary, never user data.
     */
    fun navDetail(tab: String, route: String?): String =
        "tab=${value(tab)} route=${route?.let { value(it) } ?: CrashKeys.NONE}"
}
