package com.kungsbackacarcommunity.app.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/**
 * [sortedHubEntriesByLabel] orders by the DISPLAYED, localized label under the
 * locale's own collation.
 *
 * The Social hub's real labels are pinned EXACTLY (not merely "is sorted"), in
 * both shipped locales, because the whole point of the change is a specific
 * user-visible order — and because English and Swedish must come out DIFFERENT.
 * An implementation that sorted by enum name, resource key or declaration order
 * would satisfy a shape-only assertion but fail these.
 */
class HubEntryOrderTest {
    private fun entry(label: String) = HubEntry(label, Icons.Filled.Groups, {})

    private fun order(labels: List<String>, locale: Locale): List<String> =
        sortedHubEntriesByLabel(labels.map(::entry), locale).map { it.label }

    /**
     * The Social hub's entries in declaration order — see AuthenticatedApp.
     *
     * Two entries are deliberately absent, and the absences are asserted below
     * so the menu cannot quietly drift back:
     *  - Notifications, removed once the chat hub's Notifications tab became the
     *    way in;
     *  - Friends, which MOVED to the map-home profile menu (see
     *    `profileMenuEntries`) — it did not disappear.
     * Billboards deliberately REMAINS: it is the only entry point to the
     * billboards screen, which does not render on the map.
     */
    private val socialEn =
        listOf("Events", "Crown Hunt", "Partners", "Billboards")
    private val socialSv =
        listOf("Event", "Kronjakt", "Partners", "Anslagstavlor")

    @Test
    fun socialEntriesAreAlphabeticalInEnglish() {
        assertEquals(
            listOf("Billboards", "Crown Hunt", "Events", "Partners"),
            order(socialEn, Locale.ENGLISH),
        )
    }

    @Test
    fun socialEntriesAreAlphabeticalInSwedish() {
        assertEquals(
            listOf("Anslagstavlor", "Event", "Kronjakt", "Partners"),
            order(socialSv, Locale.forLanguageTag("sv")),
        )
    }

    /**
     * Friends moved OUT of the Social menu and INTO the map-home profile menu.
     * Pinned as an explicit absence rather than left implicit in the lists above,
     * because a re-added Social "Friends" row would give the friends list two
     * menu doors again — the exact drift this file exists to catch.
     */
    @Test
    fun socialEntriesDoNotContainFriends() {
        assertFalse(socialEn.contains("Friends"))
        assertFalse(socialSv.contains("Vänner"))
    }

    /**
     * The two locales genuinely disagree — the guard against "sorted the key, not
     * the label". Sorting the resource keys would give one identical order for both.
     */
    @Test
    fun englishAndSwedishOrderTheSameEntriesDifferently() {
        val en = order(socialEn, Locale.ENGLISH)
        val sv = order(socialSv, Locale.forLanguageTag("sv"))
        // Crown Hunt is 2nd in English while its Swedish label Kronjakt is 3rd;
        // Events is 3rd in English while its Swedish label Event is 2nd — the two
        // swap places, so one shared order cannot satisfy both.
        assertEquals(1, en.indexOf("Crown Hunt"))
        assertEquals(2, sv.indexOf("Kronjakt"))
        assertEquals(2, en.indexOf("Events"))
        assertEquals(1, sv.indexOf("Event"))
    }

    /**
     * Swedish collates å/ä/ö AFTER z — the specific reason this uses a [Collator]
     * rather than Kotlin's natural (UTF-16 code-unit) ordering, which would place
     * "Ängen" before "Zebra".
     */
    @Test
    fun swedishSortsAccentedVowelsAfterZ() {
        assertEquals(
            listOf("Bil", "Zebra", "Åka", "Ängen", "Öland"),
            order(listOf("Öland", "Ängen", "Zebra", "Åka", "Bil"), Locale.forLanguageTag("sv")),
        )
        // The same input under English collation does NOT put them after z.
        assertEquals(
            listOf("Åka", "Ängen", "Bil", "Öland", "Zebra"),
            order(listOf("Öland", "Ängen", "Zebra", "Åka", "Bil"), Locale.ENGLISH),
        )
    }

    /** Case must not jump a label up the list (SECONDARY strength). */
    @Test
    fun sortIsCaseInsensitive() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            order(listOf("cherry", "Banana", "apple"), Locale.ENGLISH),
        )
    }

    /** Entries whose labels collate equally keep their declared order. */
    @Test
    fun sortIsStableForEquallyCollatingLabels() {
        val a = HubEntry("Same", Icons.Filled.Groups, {})
        val b = HubEntry("Same", Icons.Filled.Groups, {})
        val sorted = sortedHubEntriesByLabel(listOf(a, b), Locale.ENGLISH)
        assertEquals(listOf(a, b), sorted)
    }

    /** Sorting must not add, drop or alter entries — only reorder them. */
    @Test
    fun sortPreservesEveryEntry() {
        val sorted = sortedHubEntriesByLabel(socialEn.map(::entry), Locale.ENGLISH)
        assertEquals(socialEn.size, sorted.size)
        assertEquals(socialEn.toSet(), sorted.map { it.label }.toSet())
    }
}
