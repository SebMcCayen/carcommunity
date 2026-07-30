package com.kungsbackacarcommunity.app.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The COEXISTENCE contract between the two crash pipelines.
 *
 * Crashlytics installs its own [Thread.UncaughtExceptionHandler] when
 * `FirebaseApp` initializes, and [CrashReporter.install] then chains itself in
 * front of it (see `KccApplication#onCreate` for the ordering). That downstream
 * handler is a plain [Thread.UncaughtExceptionHandler], so it is exactly what a
 * recording fake stands in for here — these tests assert the property that
 * matters and would be broken by a regression: **the previously-installed
 * handler still runs, with the original throwable, on every path.**
 *
 * The Firebase SDK itself is deliberately not exercised: it cannot initialize on
 * the JVM, and mocking it would test the mock rather than the chain.
 */
class CrashReporterChainTest {

    /** Stands in for the Crashlytics handler that is default when install() runs. */
    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        val seen = mutableListOf<Throwable>()

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            seen += throwable
        }
    }

    private class RecordingReporter : DiagnosticsReporter {
        val reports = mutableListOf<DiagnosticsReport>()

        override fun report(report: DiagnosticsReport) {
            reports += report
        }
    }

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun captureOriginalHandler() {
        original = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreOriginalHandler() {
        // The default handler is process-global; leaking a test one would break
        // every later test in the same JVM.
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private fun install(reporter: DiagnosticsReporter) {
        CrashReporter.install(
            reporter = reporter,
            appVersion = "0.0.0-test",
            buildNumber = "0",
            osVersion = "test",
        )
    }

    @Test
    fun `a crash reaches BOTH the diagnostics reporter and the downstream handler`() {
        val downstream = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(downstream)
        val reporter = RecordingReporter()
        install(reporter)

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue("CrashReporter must be the default handler", installed is CrashReporter)

        val boom = IllegalStateException("boom")
        installed!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(1, reporter.reports.size)
        assertEquals(DiagnosticsSeverity.CRITICAL, reporter.reports.single().severity)
        assertEquals(1, downstream.seen.size)
        assertSame("the downstream handler must see the ORIGINAL throwable", boom, downstream.seen.single())
    }

    @Test
    fun `the downstream handler still runs when the diagnostics reporter throws`() {
        // The whole point of the `finally`: a broken diagnostics path must never
        // be the reason Crashlytics misses a crash.
        val downstream = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(downstream)
        install(DiagnosticsReporter { throw IllegalStateException("reporter is broken") })

        val boom = RuntimeException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(1, downstream.seen.size)
        assertSame(boom, downstream.seen.single())
    }

    @Test
    fun `install chains onto a NON-CrashReporter handler rather than backing off`() {
        // Regression guard for the ordering in KccApplication: Crashlytics is
        // already the default when install() runs. If the idempotency check were
        // ever loosened to "a handler is already installed", the diagnostics
        // report would silently stop happening.
        val downstream = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(downstream)
        val reporter = RecordingReporter()
        install(reporter)

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals("the diagnostics report must not be skipped", 1, reporter.reports.size)
        assertEquals(1, downstream.seen.size)
    }

    @Test
    fun `installing twice does not report the same crash twice`() {
        val downstream = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(downstream)
        val reporter = RecordingReporter()
        install(reporter)
        install(reporter)

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals(1, reporter.reports.size)
        assertEquals(1, downstream.seen.size)
    }

    @Test
    fun `the diagnostics report still carries no stack trace - that is Crashlytics' job`() {
        // The division of labour this whole change rests on. If the diagnostics
        // report ever started carrying frames, the privacy review of
        // `diagnostics-submitReport` would no longer hold.
        val downstream = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(downstream)
        val reporter = RecordingReporter()
        install(reporter)

        val boom = IllegalStateException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        val report = reporter.reports.single()
        val serialized = report.toData().toString()
        assertTrue(
            "the report must not contain stack frames",
            !serialized.contains("at com.kungsbackacarcommunity") && !serialized.contains(".kt:"),
        )
        assertEquals("IllegalStateException", report.errorCode)
    }
}
