package com.kungsbackacarcommunity.app.chattime

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One row of a rendered conversation: either a message or the centred date
 * heading that opens a day's block of messages.
 *
 * [key] is a stable LazyColumn key. Separators and messages live in one index
 * space, so the two families are prefixed apart — a message whose id happened to
 * look like a date would otherwise collide with a separator and Compose would
 * reuse the wrong node.
 */
sealed interface ChatTimelineItem<out T> {
    val key: String

    /** The heading above a day's messages. [date] is a LOCAL date (see [ChatTimeline]). */
    data class DaySeparator(val date: LocalDate) : ChatTimelineItem<Nothing> {
        override val key: String get() = "day-$date"
    }

    /** One message, carrying the caller's own model type untouched. */
    data class Message<out T>(val id: String, val message: T) : ChatTimelineItem<T> {
        override val key: String get() = "msg-$id"
    }
}

/**
 * Inserts day separators into a conversation.
 *
 * Deliberately pure and free of Compose/Android types: this is the part that is
 * easy to get subtly wrong (midnight, time zones, the pagination seam), so it is
 * a function over a list that a unit test can hit directly rather than date
 * arithmetic scattered through a composable.
 *
 * ### The rules
 *
 * - Input is expected OLDEST-FIRST, which is the order both the channel and DM
 *   streams already hand to their lists.
 * - A separator is emitted before the first message of every LOCAL date that
 *   differs from the previous dated message's — i.e. whenever the day changes,
 *   including the very first message.
 * - The date is computed in [zone], the DEVICE's zone, not UTC. Stored timestamps
 *   are epoch millis (an absolute instant), so the same message legitimately
 *   falls on different calendar days for readers in different zones, and the
 *   right answer is always the reader's own day. Midnight therefore needs no
 *   special handling: it is simply the point where `toLocalDate()` changes.
 * - A message with a NULL timestamp (an optimistic local echo whose server
 *   timestamp has not landed yet) emits no separator and does not advance the
 *   current day. It stays under whichever heading precedes it, and the real
 *   separator appears on its own the moment the timestamp resolves — better than
 *   inventing a day from the local clock and having the heading jump.
 *
 * ### Pagination
 *
 * There is no incremental path and no seam bookkeeping: older pages are prepended
 * to the same list and this runs over the WHOLE list again. The seam is therefore
 * correct by construction — if the last old message and the first already-loaded
 * message share a day they yield exactly one separator between them, and if they
 * do not, each gets its own. A version that appended separators only to the newly
 * loaded slice is precisely how a conversation ends up with the same date printed
 * twice, or with a page of history under no heading at all.
 *
 * ### Ordering
 *
 * Messages that go BACKWARDS in time (clock skew between senders) open a fresh
 * separator, because the rule is "the day changed", not "the day increased".
 * That prints the truth about a genuinely out-of-order stream rather than hiding
 * it under the wrong heading.
 */
object ChatTimeline {
    fun <T> build(
        messages: List<T>,
        zone: ZoneId,
        id: (T) -> String,
        timestampMillis: (T) -> Long?,
    ): List<ChatTimelineItem<T>> {
        if (messages.isEmpty()) return emptyList()
        // Sized for the COMMON case, not the worst one: a conversation has one
        // separator per day CHANGE, so a realistic thread is n + a handful. The
        // worst case (every message on its own day) is 2n and would grow the
        // backing array once or twice — amortised O(1), and cheaper overall than
        // making every ordinary conversation pay double the allocation up front.
        val items = ArrayList<ChatTimelineItem<T>>(messages.size + 1)
        var currentDay: LocalDate? = null
        for (message in messages) {
            val millis = timestampMillis(message)
            if (millis != null) {
                val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                if (day != currentDay) {
                    items.add(ChatTimelineItem.DaySeparator(day))
                    currentDay = day
                }
            }
            items.add(ChatTimelineItem.Message(id = id(message), message = message))
        }
        return items
    }
}
