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
            Thread.setDefaultUncaughtExceptionHandler(
                CrashReporter(reporter, appVersion, buildNumber, osVersion, previous),
            )
        }
    }
}
