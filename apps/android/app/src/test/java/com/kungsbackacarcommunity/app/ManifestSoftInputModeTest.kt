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

        val activityElement = mainActivityElement(text, manifest.path)

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
     * The locator must key off the NAME, not the position: adding any activity
     * ahead of MainActivity must not break this test, and must not let a
     * different activity's attributes stand in for MainActivity's.
     */
    @Test
    fun locatesMainActivityEvenWhenAnotherActivityComesFirst() {
        val synthetic =
            """
            <manifest>
              <application>
                <activity android:name=".SomeOtherActivity"
                          android:windowSoftInputMode="adjustPan" />
                <activity-alias android:name=".Alias" android:targetActivity=".MainActivity" />
                <activity android:name=".MainActivity"
                          android:windowSoftInputMode="adjustResize">
                  <intent-filter />
                </activity>
              </application>
            </manifest>
            """.trimIndent()

        val element = mainActivityElement(synthetic, "synthetic")

        assertTrue("Located the wrong element:\n$element", element.contains("\".MainActivity\""))
        assertTrue(
            "Should not have picked up the decoy activity:\n$element",
            !element.contains("adjustPan"),
        )
        assertTrue(element.contains("android:windowSoftInputMode=\"adjustResize\""))
    }

    /**
     * Teeth: the attribute genuinely has to be on MainActivity. A manifest where
     * only some OTHER activity declares adjustResize must not satisfy the
     * locator's element.
     */
    @Test
    fun anotherActivitysAdjustResizeDoesNotCount() {
        val synthetic =
            """
            <manifest>
              <application>
                <activity android:name=".SomeOtherActivity"
                          android:windowSoftInputMode="adjustResize" />
                <activity android:name=".MainActivity" android:exported="true" />
              </application>
            </manifest>
            """.trimIndent()

        val element = mainActivityElement(synthetic, "synthetic")

        assertTrue(
            "The MainActivity element must not inherit another activity's " +
                "declaration:\n$element",
            !element.contains("android:windowSoftInputMode"),
        )
    }

    @Test
    fun missingMainActivityIsAnError() {
        val synthetic = "<manifest><application><activity android:name=\".Other\" /></application></manifest>"
        val failure =
            runCatching { mainActivityElement(synthetic, "synthetic") }.exceptionOrNull()
        assertTrue(
            "Expected an AssertionError, got $failure",
            failure is AssertionError,
        )
    }

    /**
     * The `<activity>` element that declares `android:name=".MainActivity"`, up to
     * its opening tag's closing `>`.
     *
     * Located by NAME rather than by position (`indexOf("<activity")` would pin the
     * test to MainActivity being the FIRST activity in the file, so any unrelated
     * activity added ahead of it would fail this test while the invariant still
     * held). Scoped to the one element so the attribute cannot be satisfied by some
     * other component's declaration elsewhere in the file.
     *
     * `<activity-alias` is excluded — the tag name must be followed by whitespace.
     */
    private fun mainActivityElement(text: String, path: String): String {
        var searchFrom = 0
        while (true) {
            val start = text.indexOf("<activity", searchFrom)
            if (start < 0) break
            searchFrom = start + "<activity".length
            // Reject `<activity-alias` (and any other longer tag name).
            if (text.getOrNull(searchFrom)?.isWhitespace() != true) continue
            val end = text.indexOf('>', start)
            if (end < 0) continue
            // end + 1 so the element string INCLUDES the closing '>', matching
            // the KDoc and making the assertion failure output a complete tag.
            val element = text.substring(start, end + 1)
            if (element.contains("android:name=\".MainActivity\"")) return element
        }
        throw AssertionError(
            "No <activity> element declaring android:name=\".MainActivity\" found in $path.",
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
