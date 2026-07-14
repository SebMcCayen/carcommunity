package com.kungsbackacarcommunity.app.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure changelog logic: bundled-JSON parsing, the
 * "Vad är nytt" page's newest-first/limit selection, and the after-update
 * popup decision (first install vs update vs multi-version skip).
 */
class ChangelogTest {

    private fun entry(
        versionCode: Int,
        versionName: String = "0.$versionCode.0",
        releaseDate: String = "2026-07-%02d".format(versionCode),
        highlights: List<String> = listOf("h$versionCode"),
        changes: List<String> = listOf("c$versionCode"),
    ) = ChangelogEntry(versionCode, versionName, releaseDate, highlights, changes)

    // --- parsing ----------------------------------------------------------

    @Test
    fun `parse reads entries newest-first regardless of file order`() {
        val json =
            """
            {
              "note": "maintainer note",
              "entries": [
                {"versionCode": 5, "versionName": "0.2.0", "releaseDate": "2026-07-10",
                 "highlights": ["Ny karta"], "changes": ["Ny karta", "Trafiklager"]},
                {"versionCode": 7, "versionName": "0.4.0", "releaseDate": "2026-07-12",
                 "highlights": ["Bilprofiler"], "changes": ["Bilprofiler i garaget"]}
              ]
            }
            """.trimIndent()
        val parsed = Changelog.parse(json)
        assertEquals(listOf(7, 5), parsed.map { it.versionCode })
        assertEquals("0.4.0", parsed[0].versionName)
        assertEquals("2026-07-12", parsed[0].releaseDate)
        assertEquals(listOf("Bilprofiler"), parsed[0].highlights)
        assertEquals(listOf("Ny karta", "Trafiklager"), parsed[1].changes)
    }

    @Test
    fun `parse skips malformed entries instead of failing the whole changelog`() {
        val json =
            """
            {
              "entries": [
                {"versionCode": 3, "versionName": "0.1.0", "releaseDate": "2026-07-09",
                 "highlights": [], "changes": ["ok"]},
                {"versionName": "no-code", "releaseDate": "2026-07-09"},
                {"versionCode": 4, "versionName": "", "releaseDate": "2026-07-09"},
                "not-an-object"
              ]
            }
            """.trimIndent()
        assertEquals(listOf(3), Changelog.parse(json).map { it.versionCode })
    }

    @Test
    fun `parse de-duplicates entries sharing a versionCode`() {
        val json =
            """
            {
              "entries": [
                {"versionCode": 5, "versionName": "0.2.0", "releaseDate": "2026-07-10",
                 "highlights": ["a"], "changes": ["a"]},
                {"versionCode": 5, "versionName": "0.2.0-dup", "releaseDate": "2026-07-10",
                 "highlights": ["b"], "changes": ["b"]},
                {"versionCode": 7, "versionName": "0.4.0", "releaseDate": "2026-07-12",
                 "highlights": ["c"], "changes": ["c"]}
              ]
            }
            """.trimIndent()
        assertEquals(listOf(7, 5), Changelog.parse(json).map { it.versionCode })
    }

    @Test
    fun `parse of a document without entries yields an empty changelog`() {
        assertTrue(Changelog.parse("""{"note": "x"}""").isEmpty())
    }

    // --- page selection (last 10 updates, newest first) --------------------

    @Test
    fun `latestEntries keeps only the newest ten releases`() {
        val twelve = (1..12).map { entry(it) }.shuffled()
        val page = Changelog.latestEntries(twelve)
        assertEquals((12 downTo 3).toList(), page.map { it.versionCode })
    }

    @Test
    fun `latestEntries returns everything when fewer than the limit exist`() {
        val six = listOf(1, 2, 3, 5, 6, 7).map { entry(it) }
        assertEquals(
            listOf(7, 6, 5, 3, 2, 1),
            Changelog.latestEntries(six).map { it.versionCode },
        )
    }

    // --- popup decision -----------------------------------------------------

    @Test
    fun `first install (no stored version) never announces`() {
        assertNull(
            Changelog.announcementFor(
                entries = listOf(entry(7)),
                lastSeenVersionCode = null,
                currentVersionCode = 7,
            ),
        )
    }

    @Test
    fun `no announcement when the version was already seen`() {
        val entries = listOf(entry(7), entry(6))
        assertNull(Changelog.announcementFor(entries, lastSeenVersionCode = 7, currentVersionCode = 7))
        // Downgrade/rollback must also stay silent.
        assertNull(Changelog.announcementFor(entries, lastSeenVersionCode = 8, currentVersionCode = 7))
    }

    @Test
    fun `single-version update announces that version without the more-hint`() {
        val announcement =
            Changelog.announcementFor(
                entries = listOf(entry(7), entry(6), entry(5)),
                lastSeenVersionCode = 6,
                currentVersionCode = 7,
            )!!
        assertEquals(7, announcement.entry.versionCode)
        assertFalse(announcement.includesEarlierVersions)
    }

    @Test
    fun `skipping several versions announces the newest with the more-hint`() {
        val announcement =
            Changelog.announcementFor(
                entries = listOf(entry(7), entry(6), entry(5), entry(3)),
                lastSeenVersionCode = 3,
                currentVersionCode = 7,
            )!!
        assertEquals(7, announcement.entry.versionCode)
        assertTrue(announcement.includesEarlierVersions)
    }

    @Test
    fun `updated but no changelog entry for the new version stays silent`() {
        assertNull(
            Changelog.announcementFor(
                entries = listOf(entry(5)),
                lastSeenVersionCode = 6,
                currentVersionCode = 7,
            ),
        )
    }

    @Test
    fun `entries newer than the running version are never announced`() {
        // A note shipped ahead of its release (e.g. merged early) must not pop
        // up until the app actually runs that version.
        val announcement =
            Changelog.announcementFor(
                entries = listOf(entry(8), entry(7)),
                lastSeenVersionCode = 6,
                currentVersionCode = 7,
            )!!
        assertEquals(7, announcement.entry.versionCode)
        assertFalse(announcement.includesEarlierVersions)
    }

    @Test
    fun `update announcement falls back to an older unseen entry when the current version has none`() {
        // Updated 5 -> 7 but only version 6 has notes: announce 6 (it is still
        // unseen) rather than staying silent.
        val announcement =
            Changelog.announcementFor(
                entries = listOf(entry(6), entry(5)),
                lastSeenVersionCode = 5,
                currentVersionCode = 7,
            )!!
        assertEquals(6, announcement.entry.versionCode)
        assertFalse(announcement.includesEarlierVersions)
    }
}
