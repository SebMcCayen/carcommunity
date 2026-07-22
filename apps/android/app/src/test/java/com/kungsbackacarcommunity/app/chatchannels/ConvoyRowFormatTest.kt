package com.kungsbackacarcommunity.app.chatchannels

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure Convoys-tab list helpers (grouping, sorting, titles). */
class ConvoyRowFormatTest {

    private fun convoy(
        id: String,
        status: String,
        createdAtMillis: Long? = null,
        names: List<String> = emptyList(),
    ) = ChatConvoy(
        convoyId = id,
        title = null,
        status = status,
        memberCount = names.size,
        memberNames = names,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun `phase splits ended from forming and active`() {
        assertEquals(ConvoyRowFormat.Phase.ONGOING, ConvoyRowFormat.phase("forming"))
        assertEquals(ConvoyRowFormat.Phase.ONGOING, ConvoyRowFormat.phase("active"))
        assertEquals(ConvoyRowFormat.Phase.PAST, ConvoyRowFormat.phase("ended"))
        // Unknown/blank status is treated as ongoing rather than hidden as history.
        assertEquals(ConvoyRowFormat.Phase.ONGOING, ConvoyRowFormat.phase("weird"))
    }

    @Test
    fun `group separates ongoing from past, each newest-created first`() {
        val list =
            listOf(
                convoy("endedOld", "ended", createdAtMillis = 100),
                convoy("active", "active", createdAtMillis = 500),
                convoy("endedNew", "ended", createdAtMillis = 300),
                convoy("forming", "forming", createdAtMillis = 400),
            )
        val grouped = ConvoyRowFormat.group(list)

        // Ongoing = forming + active, newest first.
        assertEquals(listOf("active", "forming"), grouped.ongoing.map { it.convoyId })
        // Past = ended only, newest first.
        assertEquals(listOf("endedNew", "endedOld"), grouped.past.map { it.convoyId })
    }

    @Test
    fun `group sorts convoys without a timestamp last`() {
        val grouped =
            ConvoyRowFormat.group(
                listOf(
                    convoy("noTime", "active", createdAtMillis = null),
                    convoy("timed", "active", createdAtMillis = 10),
                ),
            )
        assertEquals(listOf("timed", "noTime"), grouped.ongoing.map { it.convoyId })
    }

    @Test
    fun `memberLabel shows up to two names and counts the overflow`() {
        val label = ConvoyRowFormat.memberLabel(listOf("Alice", "Bob", "Cara", "Dan"))
        assertEquals(listOf("Alice", "Bob"), label.shownNames)
        assertEquals(2, label.overflow)
    }

    @Test
    fun `memberLabel drops blank names and has no overflow when it fits`() {
        val label = ConvoyRowFormat.memberLabel(listOf("Alice", "  ", "Bob"))
        assertEquals(listOf("Alice", "Bob"), label.shownNames)
        assertEquals(0, label.overflow)
    }

    @Test
    fun `memberLabel is empty when there are no usable names`() {
        val label = ConvoyRowFormat.memberLabel(listOf("", "   "))
        assertEquals(emptyList<String>(), label.shownNames)
        assertEquals(0, label.overflow)
    }

    @Test
    fun `createdAtLabel renders date and 24-hour time in the reader's zone`() {
        val millis = Instant.parse("2020-07-22T12:05:00Z").toEpochMilli()
        val label =
            ConvoyRowFormat.createdAtLabel(
                millis = millis,
                // UTC+2 in July → 14:05 local.
                zone = ZoneId.of("Europe/Stockholm"),
                locale = Locale.ENGLISH,
                use24Hour = true,
                datePattern = "d MMM",
            )
        assertEquals("22 Jul 14:05", label)
    }

    @Test
    fun `createdAtLabel honours the 12-hour setting`() {
        val millis = Instant.parse("2020-07-22T12:05:00Z").toEpochMilli()
        val label =
            ConvoyRowFormat.createdAtLabel(
                millis = millis,
                zone = ZoneId.of("Europe/Stockholm"),
                locale = Locale.ENGLISH,
                use24Hour = false,
                datePattern = "d MMM",
            )
        assertEquals("22 Jul 2:05 PM", label)
    }
}
