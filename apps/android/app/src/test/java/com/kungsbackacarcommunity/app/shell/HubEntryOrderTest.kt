package com.kungsbackacarcommunity.app.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import org.junit.Assert.assertEquals
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

    /** The Social hub's entries in declaration order — see AuthenticatedApp. */
    private val socialEn =
        listOf("Friends", "Events", "Notifications", "Crown Hunt", "Partners", "Billboards")
    private val socialSv =
        listOf("Vänner", "Event", "Aviseringar", "Kronjakt", "Partners", "Anslagstavlor")

    @Test
    fun socialEntriesAreAlphabeticalInEnglish() {
        assertEquals(
            listOf("Billboards", "Crown Hunt", "Events", "Friends", "Notifications", "Partners"),
            order(socialEn, Locale.ENGLISH),
        )
    }

    @Test
    fun socialEntriesAreAlphabeticalInSwedish() {
        assertEquals(
            listOf("Anslagstavlor", "Aviseringar", "Event", "Kronjakt", "Partners", "Vänner"),
            order(socialSv, Locale.forLanguageTag("sv")),
        )
    }

    /**
     * The two locales genuinely disagree — the guard against "sorted the key, not
     * the label". Sorting the resource keys would give one identical order for both.
     */
    @Test
    fun englishAndSwedishOrderTheSameEntriesDifferently() {
        val en = order(socialEn, Locale.ENGLISH)
        val sv = order(socialSv, Locale.forLanguageTag("sv"))
        // Friends/Vänner is 4th in English but last in Swedish; Notifications is 5th
        // in English while its Swedish label Aviseringar is 2nd.
        assertEquals(3, en.indexOf("Friends"))
        assertEquals(5, sv.indexOf("Vänner"))
        assertEquals(4, en.indexOf("Notifications"))
        assertEquals(1, sv.indexOf("Aviseringar"))
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
