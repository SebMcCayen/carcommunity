package com.kungsbackacarcommunity.app.diagnostics

/**
 * Installs an uncaught-exception hook (Phase 12 slice 22) that submits a
 * PII-safe `critical` diagnostics report and then delegates to whatever
 * handler was previously installed (so normal crash behavior is preserved).
 *
 * Submission during a crash is best-effort — the process may die before the
 * callable flushes — but the report is enqueued synchronously on the crashing
 * thread first. The handler never throws: any failure while building or
 * reporting is swallowed so the original crash surfaces unchanged.
 *
 * ## Coexistence with Crashlytics
 *
 * This is NOT the app's only crash handler, and is not meant to be. The
 * Crashlytics SDK installs its OWN [Thread.UncaughtExceptionHandler] when
 * `FirebaseApp` initializes; `KccApplication` deliberately touches Crashlytics
 * first and calls [install] afterwards, so one uncaught exception runs:
 *
 *   this reporter (PII-safe report, no stack trace)
 *     -> the Crashlytics handler (full stack trace + breadcrumbs + custom keys)
 *       -> the platform's original handler (the process dies as usual)
 *
 * [delegate] is what makes that work, and the `finally` guarantees the chain
 * continues even when the diagnostics report itself throws — so this reporter
 * can never be the reason Crashlytics misses a crash.
 *
 * The idempotency guard in [install] is deliberately `previous is CrashReporter`
 * and NOT "a handler is already installed": by the time [install] runs, the
 * Crashlytics handler IS the default, and treating that as "already installed"
 * would silently disable the diagnostics report.
 *
 * See [CrashTelemetry] for what each of the two pipelines is for.
 */
class CrashReporter internal constructor(
    private val reporter: DiagnosticsReporter,
    private val appVersion: String?,
    private val buildNumber: String?,
    private val osVersion: String?,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            reporter.report(
                DiagnosticsReports.fromThrowable(
                    throwable = throwable,
                    featureArea = DiagnosticsFeatureArea.UNKNOWN,
                    appVersion = appVersion,
                    buildNumber = buildNumber,
                    osVersion = osVersion,
                ),
            )
        } catch (error: Exception) {
            // Never mask the original crash.
        } finally {
            delegate?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /**
         * Chains a [CrashReporter] in front of the current default handler.
         * Safe to call once from Application#onCreate.
         */
        fun install(
            reporter: DiagnosticsReporter,
            appVersion: String?,
            buildNumber: String?,
            osVersion: String?,
        ) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            // Idempotent: if a CrashReporter is already the default handler,
            // don't chain another (which would report the same crash twice).
            if (previous is CrashReporter) return
            Thread.setDefaultUncaughtExceptionHandler(
                CrashReporter(reporter, appVersion, buildNumber, osVersion, previous),
            )
        }
    }
}
