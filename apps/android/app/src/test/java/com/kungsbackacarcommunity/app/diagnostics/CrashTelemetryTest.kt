package com.kungsbackacarcommunity.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Crashlytics seam: the debug-collection decision, the
 * key/breadcrumb bounding, and the PII stance on anything user-derived.
 * Deliberately Firebase-free — the SDK is behind [CrashTelemetry].
 */
class CrashTelemetryTest {

    // --- collection policy ---------------------------------------------------

    @Test
    fun `collection is disabled in debug builds`() {
        assertFalse(CrashTelemetryPolicy.collectionEnabled(isDebugBuild = true))
    }

    @Test
    fun `collection is enabled in release builds`() {
        assertTrue(CrashTelemetryPolicy.collectionEnabled(isDebugBuild = false))
    }

    // --- value bounding ------------------------------------------------------

    @Test
    fun `value collapses whitespace and newlines to single spaces`() {
        // A newline in a Crashlytics key/value corrupts the rendered report.
        assertEquals("a b c", CrashTelemetryText.value("a\n b\t\tc"))
    }

    @Test
    fun `value trims`() {
        assertEquals("Map", CrashTelemetryText.value("  Map  "))
    }

    @Test
    fun `value is capped at the Crashlytics bound`() {
        val capped = CrashTelemetryText.value("x".repeat(CrashTelemetryText.MAX_LENGTH * 2))
        assertEquals(CrashTelemetryText.MAX_LENGTH, capped.length)
    }

    // --- PII stance on user-derived text ------------------------------------

    @Test
    fun `userDerived masks emails uuids paths and digit runs`() {
        val masked =
            CrashTelemetryText.userDerived(
                "failed for a@b.com at /data/user/0/files with id " +
                    "123e4567-e89b-12d3-a456-426614174000 after 4711 tries",
            )
        assertFalse(masked.contains("a@b.com"))
        assertFalse(masked.contains("123e4567"))
        assertFalse(masked.contains("/data/user"))
        assertFalse(masked.contains("4711"))
        assertTrue(masked.contains("<email>"))
        assertTrue(masked.contains("<uuid>"))
        assertTrue(masked.contains("<path>"))
        assertTrue(masked.contains("<n>"))
    }

    @Test
    fun `userDerived is capped at the Crashlytics bound`() {
        // Digit runs collapse to "<n>", so use a non-digit body to test the cap.
        val capped = CrashTelemetryText.userDerived("y".repeat(CrashTelemetryText.MAX_LENGTH * 2))
        assertEquals(CrashTelemetryText.MAX_LENGTH, capped.length)
    }

    // --- breadcrumbs ---------------------------------------------------------

    @Test
    fun `breadcrumb without a detail is just the event`() {
        assertEquals(CrashEvents.APP_START, CrashTelemetryText.breadcrumb(CrashEvents.APP_START, null))
    }

    @Test
    fun `breadcrumb with a blank detail does not render a dangling colon`() {
        assertEquals(CrashEvents.NAV, CrashTelemetryText.breadcrumb(CrashEvents.NAV, "   "))
    }

    @Test
    fun `breadcrumb joins event and detail`() {
        assertEquals("nav: tab=Map route=none", CrashTelemetryText.breadcrumb(CrashEvents.NAV, "tab=Map route=none"))
    }

    @Test
    fun `breadcrumb is capped at the Crashlytics bound`() {
        val capped = CrashTelemetryText.breadcrumb(CrashEvents.NAV, "z".repeat(CrashTelemetryText.MAX_LENGTH))
        assertEquals(CrashTelemetryText.MAX_LENGTH, capped.length)
    }

    @Test
    fun `navDetail renders an absent route as none`() {
        assertEquals("tab=Map route=none", CrashTelemetryText.navDetail("Map", null))
    }

    @Test
    fun `navDetail renders the open route`() {
        assertEquals("tab=Social route=Conversations", CrashTelemetryText.navDetail("Social", "Conversations"))
    }

    // --- vocabulary ----------------------------------------------------------

    @Test
    fun `custom key names are unique and within the Crashlytics key budget`() {
        val keys =
            listOf(
                CrashKeys.BUILD_TYPE,
                CrashKeys.VERSION_NAME,
                CrashKeys.VERSION_CODE,
                CrashKeys.NAV_SDK_ENABLED,
                CrashKeys.MAPBOX_SDK_VERSION,
                CrashKeys.SHELL_TAB,
                CrashKeys.SHELL_ROUTE,
                CrashKeys.LIVE_SHARING,
                CrashKeys.LAST_NON_FATAL,
            )
        assertEquals(keys.size, keys.toSet().size)
        // Crashlytics keeps at most 64 custom keys per report.
        assertTrue(keys.size <= 64)
    }

    @Test
    fun `non-fatal feature paths are unique and stable dot-paths`() {
        val features =
            listOf(
                CrashFeatures.LIVE_NEARBY_REFRESH,
                CrashFeatures.LIVE_SESSION_LISTENER,
                CrashFeatures.NAV_ORIGIN,
                CrashFeatures.DM_SEND,
                CrashFeatures.CHANNEL_SEND,
            )
        assertEquals(features.size, features.toSet().size)
        features.forEach { assertTrue(it, it.matches(Regex("""[a-z]+\.[a-zA-Z]+"""))) }
    }

    // --- the no-op ------------------------------------------------------------

    @Test
    fun `the no-op telemetry never throws`() {
        NoopCrashTelemetry.setKey(CrashKeys.SHELL_TAB, "Map")
        NoopCrashTelemetry.log(CrashEvents.NAV, "tab=Map")
        NoopCrashTelemetry.recordNonFatal(CrashFeatures.DM_SEND, IllegalStateException("boom"))
    }
}
