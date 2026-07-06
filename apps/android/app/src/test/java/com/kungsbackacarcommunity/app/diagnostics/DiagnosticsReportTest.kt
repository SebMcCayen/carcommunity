package com.kungsbackacarcommunity.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    @Test
    fun `sanitizeMessage masks emails, uuids, paths and digit runs`() {
        val raw =
            "Login failed for jane.doe@example.com id 550e8400-e29b-41d4-a716-446655440000 " +
                "at /data/user/0/app/cache code 429"
        val result = DiagnosticsReports.sanitizeMessage(raw)
        assertFalse("email removed", result.contains("jane.doe@example.com"))
        assertFalse("uuid removed", result.contains("550e8400"))
        assertFalse("no raw digits", result.contains("429"))
        assertTrue(result.contains("<email>"))
        assertTrue(result.contains("<uuid>"))
        assertTrue(result.contains("<path>"))
        assertTrue(result.contains("<n>"))
    }

    @Test
    fun `sanitizeMessage collapses whitespace and caps at the contract length`() {
        val raw = "a\n\n   b\t\tc " + "x".repeat(5000)
        val result = DiagnosticsReports.sanitizeMessage(raw)
        assertTrue(result.startsWith("a b c"))
        assertEquals(DiagnosticsReports.MAX_SAFE_MESSAGE_LENGTH, result.length)
    }

    @Test
    fun `fromThrowable builds a critical android report with a class-prefixed message`() {
        val report =
            DiagnosticsReports.fromThrowable(
                throwable = IllegalStateException("boom at 42"),
                appVersion = "0.1.0",
                buildNumber = "7",
                osVersion = "14",
            )
        assertEquals(DiagnosticsSeverity.CRITICAL, report.severity)
        assertEquals(DiagnosticsFeatureArea.UNKNOWN, report.featureArea)
        assertEquals("IllegalStateException", report.errorCode)
        assertEquals("IllegalStateException: boom at <n>", report.safeMessage)
        assertEquals("android", report.toData()["platform"])
    }

    @Test
    fun `fromThrowable falls back to the class name when the message is blank`() {
        val report = DiagnosticsReports.fromThrowable(RuntimeException())
        assertEquals("RuntimeException", report.safeMessage)
        assertTrue(report.safeMessage.isNotEmpty())
    }

    @Test
    fun `toData omits absent optionals but always includes the required fields`() {
        val data =
            DiagnosticsReport(
                severity = DiagnosticsSeverity.ERROR,
                featureArea = DiagnosticsFeatureArea.NETWORK,
                safeMessage = "timeout",
            ).toData()
        assertEquals("error", data["severity"])
        assertEquals("android", data["platform"])
        assertEquals("network", data["featureArea"])
        assertEquals("timeout", data["safeMessage"])
        assertFalse(data.containsKey("appVersion"))
        assertFalse(data.containsKey("errorCode"))
    }

    @Test
    fun `crash reporter submits a report and then delegates to the previous handler`() {
        val captured = mutableListOf<DiagnosticsReport>()
        val recording = DiagnosticsReporter { captured.add(it) }
        var delegated = false
        val delegate =
            Thread.UncaughtExceptionHandler { _, _ -> delegated = true }

        val reporter =
            CrashReporter(
                reporter = recording,
                appVersion = "0.1.0",
                buildNumber = "7",
                osVersion = "14",
                delegate = delegate,
            )
        reporter.uncaughtException(Thread.currentThread(), IllegalArgumentException("bad id 99"))

        assertEquals(1, captured.size)
        assertEquals(DiagnosticsSeverity.CRITICAL, captured.single().severity)
        assertTrue("original handler still runs", delegated)
    }

    @Test
    fun `crash reporter never throws even if the sink fails, and still delegates`() {
        val throwing = DiagnosticsReporter { throw IllegalStateException("sink down") }
        var delegated = false
        val delegate = Thread.UncaughtExceptionHandler { _, _ -> delegated = true }

        val reporter = CrashReporter(throwing, null, null, null, delegate)
        // Must not propagate the sink failure.
        reporter.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue("delegate runs despite sink failure", delegated)
    }

    @Test
    fun `noop reporter is a safe default`() {
        NoopDiagnosticsReporter.report(
            DiagnosticsReport(DiagnosticsSeverity.INFO, DiagnosticsFeatureArea.UNKNOWN, "x"),
        )
        assertNull(null)
    }
}
