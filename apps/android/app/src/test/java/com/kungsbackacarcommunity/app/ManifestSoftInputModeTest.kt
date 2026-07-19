package com.kungsbackacarcommunity.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the one manifest attribute that decides whether the chat composer lands
 * above the keyboard or halfway up the screen.
 *
 * Without `android:windowSoftInputMode="adjustResize"` the window defaults to
 * SOFT_INPUT_ADJUST_UNSPECIFIED, which the framework may resolve to legacy
 * ADJUST_PAN. Pan lifts the WHOLE window by the keyboard's height while the chat
 * composer ALSO pads itself by `WindowInsets.ime` — two lifts, so the input
 * settled around the middle of the screen and the message list went with it.
 *
 * Asserted against the manifest SOURCE rather than a running window because the
 * failure mode is a missing declaration, and because no instrumentation
 * environment available here reliably raises a soft IME (see `ChatHubInsetsTest`,
 * which documents the emulator refusing to show one at all). A source assertion
 * is blunt but it is exactly the invariant: the attribute must be declared on
 * MainActivity.
 */
class ManifestSoftInputModeTest {
    @Test
    fun mainActivityDeclaresAdjustResize() {
        val manifest = findManifest()
        val text = manifest.readText()

        // The <activity> element for MainActivity, up to its closing '>'. Scoped to
        // that element so the attribute cannot be satisfied by some other
        // component's declaration elsewhere in the file.
        val activityStart = text.indexOf("<activity")
        assertTrue("No <activity> element found in ${manifest.path}.", activityStart >= 0)
        val activityElement = text.substring(activityStart, text.indexOf('>', activityStart))

        assertTrue(
            "The MainActivity element must name .MainActivity — this test located " +
                "the wrong element:\n$activityElement",
            activityElement.contains("android:name=\".MainActivity\""),
        )
        assertTrue(
            "MainActivity must declare android:windowSoftInputMode=\"adjustResize\". " +
                "Without it the window may fall back to ADJUST_PAN, which lifts the " +
                "whole window by the keyboard height on top of the chat composer's " +
                "own imePadding — the input then floats mid-screen. Found:\n" +
                activityElement,
            activityElement.contains("android:windowSoftInputMode=\"adjustResize\""),
        )
    }

    /**
     * Walks up from the test's working directory to the `app` module, so the test
     * does not depend on Gradle's choice of working directory for the JVM test
     * task (it is the module dir today, but that is not contractual).
     */
    private fun findManifest(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/AndroidManifest.xml")
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/src/main/AndroidManifest.xml")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw AssertionError(
            "Could not locate app/src/main/AndroidManifest.xml from " +
                System.getProperty("user.dir"),
        )
    }
}
