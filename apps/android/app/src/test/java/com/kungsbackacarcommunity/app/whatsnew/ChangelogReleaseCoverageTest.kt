package com.kungsbackacarcommunity.app.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The RELEASE CONTRACT guard: the bundled changelog must contain an entry for the
 * versionCode the app currently ships.
 *
 * This exists because the contract was documented but not enforced, and so was
 * missed: `changelog.json` was authored once (up to versionCode 7 / 0.4.0) and
 * never appended again, while the app shipped 8, 9, 10 and 11. The update popup's
 * logic ([Changelog.announcementFor]) is correct and degrades silently when there
 * is nothing to announce, so the omission was invisible — every user who updated
 * to 0.5.0–0.8.0 simply got no "Vad är nytt" popup, and nothing failed.
 *
 * A doc alone ("every release PR MUST append an entry" — see the `note` field in
 * `changelog.json` and apps/android/README.md § Changelog) cannot catch that. This
 * test does: any PR that bumps `versionCode` without adding the matching entry
 * fails CI.
 *
 * Reads the sources as FILES rather than through `BuildConfig`/resources so it
 * stays a plain JVM unit test (no Robolectric/instrumentation), matching the rest
 * of the changelog tests.
 */
class ChangelogReleaseCoverageTest {
    private val moduleDir: File = resolveModuleDir()

    private val buildFile = File(moduleDir, "build.gradle.kts")
    private val changelogFile = File(moduleDir, "src/main/res/raw/changelog.json")

    @Test
    fun bundledChangelogHasAnEntryForTheShippingVersion() {
        val versionCode = shippingVersionCode()
        val versionName = shippingVersionName()
        val entries = parsedEntries()
        val entry = entries.firstOrNull { it.versionCode == versionCode }

        assertNotNull(
            "No changelog entry for the shipping versionCode $versionCode " +
                "($versionName). Every release PR that bumps versionCode MUST append an " +
                "entry to app/src/main/res/raw/changelog.json — without one the " +
                "after-update \"Vad är nytt\" popup stays silent for this release. " +
                "Newest entry present: ${entries.firstOrNull()?.versionCode}.",
            entry,
        )

        // The entry must describe THIS release, not merely carry the right number.
        assertEquals(
            "changelog entry $versionCode has the wrong versionName",
            versionName,
            entry!!.versionName,
        )
        assertTrue(
            "changelog entry $versionCode must list at least one change",
            entry.changes.isNotEmpty(),
        )
        assertTrue(
            "changelog entry $versionCode must list at least one highlight — the " +
                "after-update popup shows highlights, so an entry without them " +
                "announces an empty card",
            entry.highlights.isNotEmpty(),
        )
    }

    /**
     * The changelog is the popup's only source, and [Changelog.announcementFor]
     * picks the newest entry in `(lastSeen, current]`. An entry NEWER than the
     * shipping build would therefore be dead weight at best; at worst it is a
     * release note leaked before its release.
     */
    @Test
    fun bundledChangelogHasNoEntryNewerThanTheShippingVersion() {
        val versionCode = shippingVersionCode()
        val ahead = parsedEntries().filter { it.versionCode > versionCode }
        assertTrue(
            "changelog.json has entries newer than the shipping versionCode " +
                "$versionCode: ${ahead.map { it.versionCode }}",
            ahead.isEmpty(),
        )
    }

    /**
     * Every shipped release is reachable from the "Vad är nytt" page, so a user who
     * skips versions can still read what changed. Gaps below the shipping version
     * mean a release went out with no notes at all.
     */
    @Test
    fun everyShippedVersionCodeSinceTheFirstHasAnEntry() {
        val codes = parsedEntries().map { it.versionCode }.toSet()
        val newest = codes.maxOrNull()
        assertNotNull(
            "changelog.json parsed to no valid entries at all — every entry is " +
                "missing a required field, or the file is malformed.",
            newest,
        )
        // versionCode 4 reached main (5b17f72b) but was superseded by the
        // versionCode 5 "for Play release" bump (7cb04cf1) the same day, so it was
        // never a release and correctly has no notes.
        val neverReleased = setOf(4)
        val missing = (1..newest!!).filter { it !in codes && it !in neverReleased }
        assertEquals(
            "changelog.json is missing entries for released versionCodes $missing",
            emptyList<Int>(),
            missing,
        )
    }

    private fun parsedEntries(): List<ChangelogEntry> {
        assertTrue("changelog.json not found at $changelogFile", changelogFile.isFile)
        return Changelog.parse(changelogFile.readText())
    }

    /** The shipping versionCode, or a clear failure — never an NPE on a reformat. */
    private fun shippingVersionCode(): Int =
        matchInBuildFile("""versionCode\s*=\s*(\d+)""", "versionCode").toInt()

    /** The shipping versionName, or a clear failure — never an NPE on a reformat. */
    private fun shippingVersionName(): String =
        matchInBuildFile("""versionName\s*=\s*"([^"]+)"""", "versionName")

    private fun matchInBuildFile(pattern: String, what: String): String {
        assertTrue("build.gradle.kts not found at $buildFile", buildFile.isFile)
        val match = Regex(pattern).find(buildFile.readText())
        assertNotNull(
            "Could not read $what from $buildFile — has the release block been " +
                "reformatted? This guard reads it textually; update the pattern.",
            match,
        )
        return match!!.groupValues[1]
    }

    private fun resolveModuleDir(): File {
        // Gradle runs unit tests with the module dir as the working dir, but do not
        // depend on it: walk up until the changelog the module bundles appears,
        // accepting either the module dir itself or a repo/apps root above it.
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/raw/changelog.json").isFile) return dir
            val nested = File(dir, "app/src/main/res/raw/changelog.json")
            if (nested.isFile) return File(dir, "app")
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate the android app module from ${File("").absolutePath}")
    }
}
