package com.kungsbackacarcommunity.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that the reporter GLUE honours the gate — i.e. that a suppressed
 * decision really does stop the callable from being invoked, rather than merely
 * being reported as suppressed while the request goes out anyway.
 */
class FeatureHealthReporterTest {

    private class RecordingReporter : ClientErrorReporter {
        val calls = mutableListOf<Triple<String, String, String?>>()

        override fun report(feature: String, message: String, code: String?) {
            calls += Triple(feature, message, code)
        }
    }

    private fun environment(navSdkEnabled: Boolean = true) =
        FeatureHealthEnvironment(
            appVersionName = "0.8.1",
            appVersionCode = 811L,
            navSdkEnabled = navSdkEnabled,
            androidApiLevel = 34,
            mapboxMapsSdkVersion = "11.26.0",
            accessTokenPresent = true,
        )

    private fun reporter(
        sink: ClientErrorReporter?,
        online: Boolean = true,
        navSdkEnabled: Boolean = true,
    ) = FeatureHealthReporter(
        gate = FeatureHealthGate(environment(navSdkEnabled = navSdkEnabled)),
        errorReporter = sink,
        networkStatus = { online },
    )

    @Test
    fun `forwards a permitted report to the client error seam`() {
        val sink = RecordingReporter()
        val decision =
            reporter(sink).report(
                kind = FeatureHealthKind.MapRenderTimeout,
                foreground = true,
                surfaceShown = true,
            )
        assertTrue(decision is FeatureHealthDecision.Report)
        assertEquals(1, sink.calls.size)
        assertEquals("mapHealth.renderTimeout", sink.calls.single().first)
        assertEquals("MAP_RENDER_TIMEOUT@0.8.1", sink.calls.single().third)
    }

    @Test
    fun `sends nothing while offline`() {
        val sink = RecordingReporter()
        reporter(sink, online = false).report(
            kind = FeatureHealthKind.MapRenderTimeout,
            foreground = true,
            surfaceShown = true,
        )
        assertEquals(emptyList<Any>(), sink.calls)
    }

    @Test
    fun `sends nothing while backgrounded or unshown`() {
        val sink = RecordingReporter()
        val subject = reporter(sink)
        subject.report(FeatureHealthKind.MapRenderTimeout, foreground = false, surfaceShown = true)
        subject.report(FeatureHealthKind.MapRenderTimeout, foreground = true, surfaceShown = false)
        assertEquals(emptyList<Any>(), sink.calls)
    }

    @Test
    fun `sends a kind at most once per session`() {
        val sink = RecordingReporter()
        val subject = reporter(sink)
        repeat(10) {
            subject.report(
                FeatureHealthKind.MapStyleLoadFailed,
                foreground = true,
                surfaceShown = true,
            )
        }
        assertEquals(1, sink.calls.size)
    }

    @Test
    fun `a null sink never throws`() {
        // A config-less build has no Firebase, so there is no reporter. The gate
        // must still run and the call must still be harmless.
        val decision =
            reporter(sink = null).report(
                kind = FeatureHealthKind.MapRenderTimeout,
                foreground = true,
                surfaceShown = true,
            )
        assertTrue(decision is FeatureHealthDecision.Report)
    }

    @Test
    fun `a throwing sink never propagates`() {
        val throwing =
            object : ClientErrorReporter {
                override fun report(feature: String, message: String, code: String?): Unit =
                    error("transport exploded")
            }
        val decision =
            reporter(throwing).report(
                kind = FeatureHealthKind.MapRenderTimeout,
                foreground = true,
                surfaceShown = true,
            )
        assertTrue(decision is FeatureHealthDecision.Report)
    }

    @Test
    fun `a throwing connectivity check fails closed and sends nothing`() {
        // Assume-offline on error: a wrong `false` costs one missed report; a
        // wrong `true` files a public issue against a user's flaky connection.
        val sink = RecordingReporter()
        FeatureHealthReporter(
            gate = FeatureHealthGate(environment()),
            errorReporter = sink,
            networkStatus = { error("no connectivity service") },
        ).report(FeatureHealthKind.MapRenderTimeout, foreground = true, surfaceShown = true)
        assertEquals(emptyList<Any>(), sink.calls)
    }

    @Test
    fun `nav kinds send nothing on a build without the nav SDK`() {
        val sink = RecordingReporter()
        val subject = reporter(sink, navSdkEnabled = false)
        subject.report(FeatureHealthKind.NavSessionInitFailed, foreground = true, surfaceShown = true)
        subject.report(FeatureHealthKind.NavRouteRequestFailed, foreground = true, surfaceShown = true)
        assertEquals(emptyList<Any>(), sink.calls)
    }
}
