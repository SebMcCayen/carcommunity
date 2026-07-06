package com.kungsbackacarcommunity.app.diagnostics

/**
 * Diagnostics domain (Phase 12 slice 22). A crash/error report submitted to
 * the public `diagnostics-submitReport` callable, which stores it in
 * `diagnosticsReports` (admin-only read). Pure Kotlin + Firebase-free so the
 * report-building and message-sanitization logic is unit-testable.
 *
 * Privacy: the backend independently sanitizes metadata, but the CLIENT is
 * responsible for keeping `safeMessage` free of PII. [DiagnosticsReports]
 * therefore strips emails, UUIDs, long digit runs, and file paths from any
 * throwable message before it ever leaves the device. No coordinates, tokens,
 * user identifiers, or raw stack traces are ever included.
 */
data class DiagnosticsReport(
    val severity: String,
    val featureArea: String,
    val safeMessage: String,
    val errorCode: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val osVersion: String? = null,
) {
    /** Callable payload for `diagnostics-submitReport`; omits absent optionals. */
    fun toData(): Map<String, Any?> =
        buildMap {
            put("severity", severity)
            put("platform", PLATFORM_ANDROID)
            put("featureArea", featureArea)
            put("safeMessage", safeMessage)
            errorCode?.let { put("errorCode", it) }
            appVersion?.let { put("appVersion", it) }
            buildNumber?.let { put("buildNumber", it) }
            osVersion?.let { put("osVersion", it) }
        }

    companion object {
        const val PLATFORM_ANDROID = "android"
    }
}

/** Severities accepted by the backend (contracts/functions diagnostics). */
object DiagnosticsSeverity {
    const val INFO = "info"
    const val WARNING = "warning"
    const val ERROR = "error"
    const val CRITICAL = "critical"
}

/** Feature areas accepted by the backend (contracts/functions diagnostics). */
object DiagnosticsFeatureArea {
    const val AUTH = "auth"
    const val LIVE_LOCATION = "live_location"
    const val EVENTS = "events"
    const val SUBSCRIPTION = "subscription"
    const val ADMIN = "admin"
    const val MAP = "map"
    const val NETWORK = "network"
    const val UNKNOWN = "unknown"
}

/** Pure report-building helpers. */
object DiagnosticsReports {
    /** Backend cap; the client trims to the same bound (contract MAX_SAFE_MESSAGE_LENGTH). */
    const val MAX_SAFE_MESSAGE_LENGTH = 2000

    private val EMAIL = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
    private val UUID =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val UNIX_PATH = Regex("""(/[\w.\-]+){2,}""")
    private val DIGITS = Regex("""\d+""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Produces a PII-safe, bounded single-line message: emails → `<email>`,
     * UUIDs → `<uuid>`, unix-style paths → `<path>`, digit runs → `<n>`,
     * collapsed whitespace, trimmed and capped at [MAX_SAFE_MESSAGE_LENGTH].
     * Ordering matters — paths/UUIDs/emails are masked before digit runs so
     * their digits aren't shredded first.
     */
    fun sanitizeMessage(raw: String): String {
        val masked =
            raw
                .replace(EMAIL, "<email>")
                .replace(UUID, "<uuid>")
                .replace(UNIX_PATH, "<path>")
                .replace(DIGITS, "<n>")
        return WHITESPACE.replace(masked, " ").trim().take(MAX_SAFE_MESSAGE_LENGTH)
    }

    /**
     * Builds a report from an uncaught throwable. `safeMessage` is
     * `SimpleClassName: <sanitized message>` (class name only when the message
     * is blank). Never includes the stack trace. Falls back to a non-empty
     * message so the backend's `min(1)` validation always passes.
     */
    fun fromThrowable(
        throwable: Throwable,
        featureArea: String = DiagnosticsFeatureArea.UNKNOWN,
        appVersion: String? = null,
        buildNumber: String? = null,
        osVersion: String? = null,
    ): DiagnosticsReport {
        val className = throwable.javaClass.simpleName.ifBlank { "Throwable" }
        val rawMessage = throwable.message?.takeIf { it.isNotBlank() }
        val safeMessage =
            if (rawMessage == null) className else "$className: ${sanitizeMessage(rawMessage)}"
        return DiagnosticsReport(
            severity = DiagnosticsSeverity.CRITICAL,
            featureArea = featureArea,
            safeMessage = safeMessage.take(MAX_SAFE_MESSAGE_LENGTH),
            errorCode = className,
            appVersion = appVersion,
            buildNumber = buildNumber,
            osVersion = osVersion,
        )
    }
}
