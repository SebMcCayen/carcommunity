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

/**
 * Teaser-safe event summary (events/{id}) — visible to any authenticated user.
 *
 * Carries the PUBLIC map location (locationName + latitude/longitude) as of the
 * deliberate 2026-07 change: every signed-in user sees event pins on the
 * community map, so the coordinates must be on the teaser the map reads without
 * the member gate. The long description and the precise street address stay
 * member-only ([EventDetail]).
 */
data class EventSummary(
    val id: String,
    val title: String,
    val summary: String?,
    val startsAtMillis: Long?,
    val endsAtMillis: Long?,
    val approximateArea: String,
    // Public map location; null when the organiser positioned no pin. Defaulted so
    // fixtures without a location stay terse — the repository sets them from the
    // teaser, and the map layer only pins events where both coordinates are set.
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isOfficial: Boolean,
    val status: EventStatus,
    val counts: RsvpCounts,
)

/**
 * Member-gated detail (events/{id}/details/private) — the long description and
 * the precise street address only. The map location moved to the public teaser
 * ([EventSummary]); it is no longer here.
 */
data class EventDetail(
    val description: String?,
    val address: String?,
)

/**
 * Client input for creating an event — mirrors the backend `createEventRequest`
 * (contracts/schemas/events.schema.json): required `title`, `startsAt`,
 * `approximateArea`, plus the optional teaser/detail fields a user-facing form
 * exposes. Times are carried as epoch millis and serialized to ISO-8601 UTC by
 * the repository ([Events.toIsoUtc]).
 *
 * A caller who passes the member gate may drive `events-create` themselves
 * (that is any signed-in, non-suspended user while member gating is disabled):
 * the callable stamps
 * `createdByRole: 'member'` and publishes the event immediately (post-moderated
 * — admins take one down via the audited `events.cancel`). `isOfficial` is
 * forced false server-side for member-created events, which is why this input
 * carries no such field: offering a control the server silently ignores would
 * be a lie to the member.
 */
data class CreateEventInput(
    val title: String,
    val approximateArea: String,
    val startsAtMillis: Long,
    val summary: String? = null,
    val description: String? = null,
    val endsAtMillis: Long? = null,
    val locationName: String? = null,
    val address: String? = null,
    // Map-pin coordinates captured by the location picker. Both set (a positioned
    // pin) or both null (no pin) — the backend rejects a half-set pair
    // (guardCoordinatePair). Public teaser data once created.
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Why an `events-create` call failed, in domain terms. Keeps the Firebase
 * exception vocabulary out of the coordinator/UI (which stay pure Kotlin);
 * [FirebaseEventsRepository] maps the callable's error code onto this via
 * [Events.createFailureFromCode] and throws [CreateEventException].
 */
enum class CreateEventFailure {
    /**
     * The per-member cap of [Events.MEMBER_EVENT_RATE_LIMIT_PER_DAY] events per
     * rolling 24h was hit — the callable answers `resource-exhausted`. A
     * definitive, self-inflicted "come back later", not a fault.
     */
    RATE_LIMITED,

    /** Anything else (denied, offline, invalid, backend fault) — generic error. */
    UNKNOWN,
}

/** A create-event failure carrying the domain [reason]. */
class CreateEventException(
    val reason: CreateEventFailure,
    cause: Throwable? = null,
) : Exception("events-create failed: $reason", cause)

object Events {
    /**
     * Max events one member may create per rolling 24h — mirrors
     * `MEMBER_EVENT_RATE_LIMIT_MAX` in functions/src/events/events-core.ts.
     * Display-only (the server is the authority); shown so the limit message
     * states a real number instead of a vague "too many".
     */
    const val MEMBER_EVENT_RATE_LIMIT_PER_DAY = 3

    /**
     * Maximum published events the Firestore listener subscribes to (soonest
     * start first, matching [sortedForList]). Keeps the snapshot bounded as
     * the `events` collection grows without bound over the app's lifetime;
     * events starting furthest in the future simply fall off the list —
     * acceptable since the screen is a soonest-first upcoming-events feed,
     * not a full history.
     */
    const val PUBLISHED_EVENTS_QUERY_LIMIT = 200L

    /**
     * Maximum completed events the past/archive listener subscribes to (most
     * recent first, matching [sortedForPastList]). Smaller than
     * [PUBLISHED_EVENTS_QUERY_LIMIT] on purpose: `completed` is the terminal
     * state of *every* event once `events-autoClose` has run, so this
     * collection only ever grows, while interest in it falls off sharply with
     * age. The bound keeps the snapshot finite; the oldest events fall off the
     * end, which is the correct end to lose.
     */
    const val PAST_EVENTS_QUERY_LIMIT = 100L

    /**
     * Maps an `events-create` callable error code onto a [CreateEventFailure].
     * Accepts both the Firebase Android SDK's enum name (`RESOURCE_EXHAUSTED`)
     * and the wire/`HttpsError` spelling (`resource-exhausted`) so the mapping
     * is pinned by unit tests without a Firebase dependency. Case-insensitive;
     * an unknown/absent code is [CreateEventFailure.UNKNOWN].
     */
    fun createFailureFromCode(code: String?): CreateEventFailure {
        val normalized = code?.trim()?.lowercase()?.replace('_', '-') ?: return CreateEventFailure.UNKNOWN
        return if (normalized == "resource-exhausted") {
            CreateEventFailure.RATE_LIMITED
        } else {
            CreateEventFailure.UNKNOWN
        }
    }

    /**
     * RSVP is allowed only for a caller who PASSES THE MEMBER GATE, on a
     * published event — mirrors the Firestore rule on events/{id}/rsvps/{uid}
     * (owner + isActiveMember() + published). "Passes the gate" rather than
     * "is an active member" because both layers are switchable: while member
     * gating is disabled (config/MemberGating.kt and the firestore.rules
     * isActiveMember() switch) any signed-in, non-suspended user passes.
     * Cancelled/completed/draft events are not RSVP-able either way.
     */
    fun canRsvp(passesMemberGate: Boolean, status: EventStatus): Boolean =
        passesMemberGate && status == EventStatus.PUBLISHED

    /** Whether the exact-location / description detail may be requested. */
    fun canSeeDetails(passesMemberGate: Boolean, status: EventStatus): Boolean =
        passesMemberGate && status == EventStatus.PUBLISHED

    /** Published events sorted by soonest start first (nulls last, stable). */
    fun sortedForList(events: List<EventSummary>): List<EventSummary> =
        events.sortedWith(
            compareBy(nullsLast<Long>()) { it.startsAtMillis },
        )

    /**
     * Past (completed) events sorted most-recent-first — the reverse of
     * [sortedForList]. An event with no readable `startsAt` sorts LAST here as
     * well, not first: `nullsLast` is applied to the descending comparator
     * rather than reversing the ascending one, so a missing time never
     * masquerades as the newest thing in the archive.
     */
    fun sortedForPastList(events: List<EventSummary>): List<EventSummary> =
        events.sortedWith(
            compareBy(nullsLast(reverseOrder<Long>())) { it.startsAtMillis },
        )

    /** Backend field limits (events-core.ts eventFieldsSchema). */
    const val TITLE_MAX = 200
    const val AREA_MAX = 200
    const val SUMMARY_MAX = 2000
    const val DESCRIPTION_MAX = 10000
    const val LOCATION_NAME_MAX = 200
    const val ADDRESS_MAX = 400

    /**
     * Whether a [CreateEventInput] satisfies the backend's required-field and
     * length rules — a title and area that are non-blank and within bounds, and
     * (when present) an end no earlier than the start. Pure so the form and the
     * coordinator can gate submission without Firebase.
     */
    fun isValidForCreate(input: CreateEventInput): Boolean {
        val title = input.title.trim()
        val area = input.approximateArea.trim()
        if (title.isEmpty() || title.length > TITLE_MAX) return false
        if (area.isEmpty() || area.length > AREA_MAX) return false
        if ((input.summary?.length ?: 0) > SUMMARY_MAX) return false
        if ((input.description?.length ?: 0) > DESCRIPTION_MAX) return false
        if ((input.locationName?.length ?: 0) > LOCATION_NAME_MAX) return false
        if ((input.address?.length ?: 0) > ADDRESS_MAX) return false
        input.endsAtMillis?.let { if (it < input.startsAtMillis) return false }
        if (!isValidCoordinatePair(input.latitude, input.longitude)) return false
        return true
    }

    /**
     * Whether a captured pin is valid: latitude and longitude are BOTH present or
     * BOTH absent (mirrors the backend `guardCoordinatePair`), and each sits in
     * range. A half-set pair or an out-of-range value is rejected before submit so
     * the callable never has to answer `invalid-argument` for it.
     */
    fun isValidCoordinatePair(latitude: Double?, longitude: Double?): Boolean {
        if ((latitude == null) != (longitude == null)) return false
        if (latitude != null && (latitude < -90.0 || latitude > 90.0)) return false
        if (longitude != null && (longitude < -180.0 || longitude > 180.0)) return false
        return true
    }

    /**
     * Serializes epoch millis to the ISO-8601 UTC form the backend's
     * `z.string().datetime()` accepts (e.g. `2026-07-11T18:30:00Z`), truncated
     * to whole seconds so no fractional part is emitted.
     */
    fun toIsoUtc(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString()

    /**
     * Builds the `events-create` callable payload from a validated input,
     * omitting blank/absent optional fields (the callable schema is `.strict()`,
     * so only known keys are sent). Optional text is trimmed; blanks are dropped.
     */
    fun createPayload(input: CreateEventInput): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "title" to input.title.trim(),
            "approximateArea" to input.approximateArea.trim(),
            "startsAt" to toIsoUtc(input.startsAtMillis),
        )
        input.endsAtMillis?.let { payload["endsAt"] = toIsoUtc(it) }
        input.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["summary"] = it }
        input.description?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["description"] = it }
        input.locationName?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["locationName"] = it }
        input.address?.trim()?.takeIf { it.isNotEmpty() }?.let { payload["address"] = it }
        // Only send a COMPLETE pin: both coordinates or neither (the callable's
        // strict schema rejects a half-set pair). isValidForCreate has already
        // gated this, so a lone value here would be a caller bug — dropped rather
        // than sent to fail server-side.
        val lat = input.latitude
        val lng = input.longitude
        if (lat != null && lng != null) {
            payload["latitude"] = lat
            payload["longitude"] = lng
        }
        return payload
    }

    /**
     * The published, upcoming, positioned events that should appear as pins on the
     * community map, from a teaser list — the pure filter behind the map's event
     * layer. Kept here (not in the map surface) so it is JVM-unit-testable without
     * a device.
     *
     * An event qualifies only when it is:
     * - PUBLISHED — a draft is invisible to everyone and a cancelled/completed
     *   event is not a live meetup, so neither gets a pin (the map must never
     *   show a cancelled event; see the callers);
     * - POSITIONED — it has a latitude AND a longitude (a half-set pair, which the
     *   backend forbids, is treated as no pin);
     * - NOT PAST — its effective end (the explicit end, else its start) is at or
     *   after [nowMillis], so a finished event drops off the map even before the
     *   auto-close sweep flips it to `completed`. An event with no readable time
     *   at all is kept (it is published with a location; there is no basis to call
     *   it past).
     *
     * Blocking is deliberately NOT applied: a published event's location is public
     * community information, so a blocked author's published event still shows as a
     * pin (its chat and detail remain block-filtered elsewhere).
     */
    fun mapPinEvents(events: List<EventSummary>, nowMillis: Long): List<EventSummary> =
        events.filter { event ->
            event.status == EventStatus.PUBLISHED &&
                event.latitude != null &&
                event.longitude != null &&
                run {
                    val effectiveEnd = event.endsAtMillis ?: event.startsAtMillis
                    effectiveEnd == null || effectiveEnd >= nowMillis
                }
        }
}
