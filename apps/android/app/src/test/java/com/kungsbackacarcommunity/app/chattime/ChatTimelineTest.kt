package com.kungsbackacarcommunity.app.chattime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The day-separator grouping, which is the part of the timestamp work most
 * likely to be wrong: midnight, the reader's zone, and above all the PAGINATION
 * SEAM, where the naive implementation prints a date twice or loses one.
 */
class ChatTimelineTest {
    private data class Msg(val id: String, val at: Long?)

    private val stockholm: ZoneId = ZoneId.of("Europe/Stockholm")

    /** Epoch millis for a wall-clock time in [zone]. */
    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId = stockholm,
    ): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun build(messages: List<Msg>, zone: ZoneId = stockholm) =
        ChatTimeline.build(
            messages = messages,
            zone = zone,
            id = { it.id },
            timestampMillis = { it.at },
        )

    /** Compact rendering of a timeline: "day:<date>" / "msg:<id>", in order. */
    private fun render(items: List<ChatTimelineItem<Msg>>): List<String> =
        items.map { item ->
            when (item) {
                is ChatTimelineItem.DaySeparator -> "day:${item.date}"
                is ChatTimelineItem.Message -> "msg:${item.id}"
            }
        }

    @Test
    fun emptyListProducesNoItems() {
        assertEquals(emptyList<ChatTimelineItem<Msg>>(), build(emptyList()))
    }

    @Test
    fun aSingleMessageStillGetsItsHeading() {
        val items = build(listOf(Msg("a", at(2026, 7, 19, 14, 0))))

        assertEquals(listOf("day:2026-07-19", "msg:a"), render(items))
    }

    @Test
    fun messagesOnTheSameDayShareOneSeparator() {
        val items =
            build(
                listOf(
                    Msg("a", at(2026, 7, 19, 0, 1)),
                    Msg("b", at(2026, 7, 19, 12, 30)),
                    Msg("c", at(2026, 7, 19, 23, 59)),
                ),
            )

        assertEquals(listOf("day:2026-07-19", "msg:a", "msg:b", "msg:c"), render(items))
    }

    @Test
    fun crossingMidnightOpensANewSeparator() {
        // One minute apart, either side of local midnight.
        val items =
            build(
                listOf(
                    Msg("a", at(2026, 7, 19, 23, 59)),
                    Msg("b", at(2026, 7, 20, 0, 0)),
                ),
            )

        assertEquals(
            listOf("day:2026-07-19", "msg:a", "day:2026-07-20", "msg:b"),
            render(items),
        )
    }

    /**
     * The day is the READER's day. The same instant is 19 July late evening in
     * Stockholm and still 19 July afternoon in New York — but an instant just
     * after Stockholm's midnight is still the 19th in New York, so the two zones
     * must group the identical message list differently.
     */
    @Test
    fun theDayIsComputedInTheDevicesZoneNotUtc() {
        val newYork = ZoneId.of("America/New_York")
        val messages =
            listOf(
                Msg("a", at(2026, 7, 19, 23, 30)),
                Msg("b", at(2026, 7, 20, 0, 30)),
            )

        assertEquals(
            "Stockholm: the two messages straddle local midnight.",
            listOf("day:2026-07-19", "msg:a", "day:2026-07-20", "msg:b"),
            render(build(messages, stockholm)),
        )
        assertEquals(
            "New York (UTC-4 that day): both instants are still 19 July there, " +
                "so they belong under ONE heading.",
            listOf("day:2026-07-19", "msg:a", "msg:b"),
            render(build(messages, newYork)),
        )
    }

    @Test
    fun aMessageWithNoTimestampStaysUnderThePrecedingHeading() {
        // An optimistic local echo: sent, server timestamp not yet resolved.
        val items =
            build(
                listOf(
                    Msg("a", at(2026, 7, 19, 10, 0)),
                    Msg("pending", null),
                ),
            )

        assertEquals(listOf("day:2026-07-19", "msg:a", "msg:pending"), render(items))
    }

    @Test
    fun aLeadingUndatedMessageProducesNoHeadingOfItsOwn() {
        val items =
            build(
                listOf(
                    Msg("pending", null),
                    Msg("a", at(2026, 7, 19, 10, 0)),
                ),
            )

        assertEquals(listOf("msg:pending", "day:2026-07-19", "msg:a"), render(items))
    }

    @Test
    fun allUndatedMessagesProduceNoSeparators() {
        val items = build(listOf(Msg("a", null), Msg("b", null)))

        assertEquals(listOf("msg:a", "msg:b"), render(items))
    }

    // --- The pagination seam ---------------------------------------------------

    /**
     * Loading older messages must not duplicate the heading at the seam.
     *
     * The older page ends on the SAME day the loaded window begins on, so the
     * whole day must still carry exactly one heading. An implementation that
     * grouped the new page separately and concatenated would emit "19 July"
     * twice — once closing the old page, once opening the already-visible one.
     */
    @Test
    fun pagination_sameDayAcrossTheSeamKeepsExactlyOneSeparator() {
        val loaded =
            listOf(
                Msg("c", at(2026, 7, 19, 14, 0)),
                Msg("d", at(2026, 7, 19, 15, 0)),
            )
        val older =
            listOf(
                Msg("a", at(2026, 7, 19, 9, 0)),
                Msg("b", at(2026, 7, 19, 10, 0)),
            )

        val before = render(build(loaded))
        val after = render(build(older + loaded))

        assertEquals(listOf("day:2026-07-19", "msg:c", "msg:d"), before)
        assertEquals(
            listOf("day:2026-07-19", "msg:a", "msg:b", "msg:c", "msg:d"),
            after,
        )
        assertEquals(
            "Exactly one heading for the day, before and after paging.",
            1,
            after.count { it.startsWith("day:") },
        )
    }

    /**
     * The other half of the seam: when the older page ends on a DIFFERENT day, a
     * heading has to appear at the boundary. An implementation that only inserted
     * separators while walking the newly loaded slice would leave the whole
     * history under no heading at all.
     */
    @Test
    fun pagination_aDayChangeAtTheSeamGainsASeparator() {
        val loaded = listOf(Msg("c", at(2026, 7, 19, 9, 0)))
        val older = listOf(Msg("a", at(2026, 7, 18, 22, 0)))

        val after = render(build(older + loaded))

        assertEquals(
            listOf("day:2026-07-18", "msg:a", "day:2026-07-19", "msg:c"),
            after,
        )
    }

    /**
     * Paging repeatedly must converge on the same thing as loading the whole
     * conversation at once — the property that actually matters, tested against
     * every prefix rather than one hand-picked split.
     */
    @Test
    fun pagination_anySplitAgreesWithTheWholeConversation() {
        val all =
            listOf(
                Msg("a", at(2026, 7, 17, 8, 0)),
                Msg("b", at(2026, 7, 17, 23, 59)),
                Msg("c", at(2026, 7, 18, 0, 1)),
                Msg("d", at(2026, 7, 18, 12, 0)),
                Msg("e", at(2026, 7, 19, 6, 0)),
                Msg("f", at(2026, 7, 19, 7, 0)),
            )
        val whole = render(build(all))

        for (split in all.indices) {
            val older = all.subList(0, split)
            val loaded = all.subList(split, all.size)
            assertEquals(
                "Prepending the first $split message(s) as an older page must " +
                    "reproduce the whole-conversation grouping.",
                whole,
                render(build(older + loaded)),
            )
        }
    }

    // --- Keys ------------------------------------------------------------------

    /**
     * Separator and message keys share one LazyColumn index space, so they must
     * not collide — including for a message whose id is spelled like a date.
     */
    @Test
    fun separatorAndMessageKeysCannotCollide() {
        val items =
            build(
                listOf(
                    Msg("2026-07-19", at(2026, 7, 19, 9, 0)),
                    Msg("b", at(2026, 7, 19, 10, 0)),
                ),
            )
        val keys = items.map { it.key }

        assertEquals("Every row key must be distinct: $keys", keys.size, keys.toSet().size)
        assertTrue(
            "The separator's key must be present: $keys",
            keys.contains(ChatTimelineItem.DaySeparator(LocalDate.of(2026, 7, 19)).key),
        )
    }
}
