package com.kungsbackacarcommunity.app.events

/**
 * Events domain model + pure logic (Phase 12 slice 9).
 *
 * Mirrors the backend events-core contract: the event status vocabulary, the
 * RSVP status enum (going/maybe/not_going), and the teaser/detail split
 * (`events/{id}` teaser vs `events/{id}/details/private` member-gated). Pure
 * Kotlin so it is JVM-unit-testable and shared by the repository and screens.
 */

/** Event lifecycle status (events/{id}.status). */
enum class EventStatus(val wire: String) {
    DRAFT("draft"),
    PUBLISHED("published"),
    CANCELLED("cancelled"),
    COMPLETED("completed"),
    ;

    companion object {
        fun fromWire(value: String?): EventStatus? = values().firstOrNull { it.wire == value }
    }
}

/** RSVP answer (events/{id}/rsvps/{uid}.status). */
enum class RsvpStatus(val wire: String) {
    GOING("going"),
    MAYBE("maybe"),
    NOT_GOING("not_going"),
    ;

    companion object {
        fun fromWire(value: String?): RsvpStatus? = values().firstOrNull { it.wire == value }
    }
}

/** Denormalized RSVP tallies stored on the teaser event doc. */
data class RsvpCounts(val going: Int, val maybe: Int, val notGoing: Int) {
    val total: Int get() = going + maybe + notGoing

    companion object {
        val EMPTY = RsvpCounts(0, 0, 0)

        /** Reads the rsvpCounts map defensively (missing/negative → 0). */
        fun fromMap(map: Map<String, Any?>?): RsvpCounts {
            if (map == null) return EMPTY
            fun read(key: String) = (map[key] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
            return RsvpCounts(read("going"), read("maybe"), read("not_going"))
        }
    }
}

/** Teaser-safe event summary (events/{id}) — visible to any authenticated user. */
data class EventSummary(
    val id: String,
    val title: String,
    val summary: String?,
    val startsAtMillis: Long?,
    val endsAtMillis: Long?,
    val approximateArea: String,
    val isOfficial: Boolean,
    val status: EventStatus,
    val counts: RsvpCounts,
)

/** Member-gated detail (events/{id}/details/private). */
data class EventDetail(
    val description: String?,
    val locationName: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
)

object Events {
    /**
     * RSVP is allowed only for active members on a published event — mirrors
     * the Firestore rule on events/{id}/rsvps/{uid} (owner + active member +
     * published). Cancelled/completed/draft events are not RSVP-able.
     */
    fun canRsvp(isActiveMember: Boolean, status: EventStatus): Boolean =
        isActiveMember && status == EventStatus.PUBLISHED

    /** Whether the exact-location / description detail may be requested. */
    fun canSeeDetails(isActiveMember: Boolean, status: EventStatus): Boolean =
        isActiveMember && status == EventStatus.PUBLISHED

    /** Published events sorted by soonest start first (nulls last, stable). */
    fun sortedForList(events: List<EventSummary>): List<EventSummary> =
        events.sortedWith(
            compareBy(nullsLast<Long>()) { it.startsAtMillis },
        )
}
