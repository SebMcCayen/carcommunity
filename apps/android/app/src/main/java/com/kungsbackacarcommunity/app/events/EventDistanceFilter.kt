package com.kungsbackacarcommunity.app.events

import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.navigation.NavGeo

/**
 * A distance band the Events list can filter by: keep events whose pin is within
 * [maxMeters] of the viewer. [ALL] (a null [maxMeters]) is the no-filter option
 * that shows every event, including ones with no coordinates.
 *
 * The bands are coarse on purpose — a car-community meetup is a "is it in my
 * town / my county / a road-trip away" decision, not a precise radius — so three
 * round kilometre bands cover the useful range without a slider's fiddliness.
 */
enum class DistanceBand(val maxMeters: Double?) {
    /** No distance filter — every event, coordinates or not. */
    ALL(null),
    WITHIN_5_KM(5_000.0),
    WITHIN_25_KM(25_000.0),
    WITHIN_50_KM(50_000.0),
}

/** An event paired with its great-circle distance from the viewer, if known. */
data class EventWithDistance(
    val event: EventSummary,
    /** Metres from the viewer to the event pin; null when either has no position. */
    val distanceMeters: Double?,
)

/**
 * Pure distance-filtering for the Events list (haversine via [NavGeo]). Kept out
 * of the composable so the band boundaries, null-location and no-coordinate
 * handling are all JVM-unit-testable without a device.
 *
 * Design decisions (documented so callers and the UI agree):
 *  - **[DistanceBand.ALL]** returns the list UNCHANGED — same events, same order.
 *    The list arrives already sorted upstream (soonest-first upcoming, most-recent
 *    -first past); ALL must not disturb that chronological ordering.
 *  - **No location** (`userLocation == null`: permission denied, or no fix yet)
 *    means "nearest" has no meaning, so a distance band cannot be honoured. Rather
 *    than silently emptying the list, [apply] returns every event unchanged for
 *    ANY band. The UI additionally disables the distance chips and shows a hint,
 *    so this branch is only ever a defensive fallback.
 *  - **No coordinates** on an event (the organiser placed no pin) means its
 *    distance is unknown. Such an event is shown under [DistanceBand.ALL] but is
 *    EXCLUDED from every distance band — it cannot be asserted to be within a
 *    radius it has no position for.
 *  - **Within a distance band** the surviving events are sorted NEAREST-FIRST,
 *    because a radius query is a "what's closest to me" question. The sort is
 *    stable, so two equidistant events keep their upstream relative order.
 */
object EventDistanceFilter {
    /** The viewer as a [LatLng], or null when [latitude]/[longitude] is absent. */
    private fun EventSummary.pin(): LatLng? {
        val lat = latitude
        val lng = longitude
        return if (lat != null && lng != null) LatLng(longitude = lng, latitude = lat) else null
    }

    /**
     * Great-circle metres from [userLocation] to the event's pin, or null when
     * either the viewer's location or the event's pin is missing.
     */
    fun distanceMetersOrNull(event: EventSummary, userLocation: LatLng?): Double? {
        val from = userLocation ?: return null
        val to = event.pin() ?: return null
        return NavGeo.distanceMeters(from, to)
    }

    /**
     * The [events] filtered and sorted for the selected [band] and [userLocation].
     * See the class KDoc for the ALL / null-location / no-coordinate rules.
     */
    fun apply(
        events: List<EventSummary>,
        userLocation: LatLng?,
        band: DistanceBand,
    ): List<EventSummary> {
        val max = band.maxMeters
        // ALL band, or no location to measure from: honour the list as-is.
        if (max == null || userLocation == null) return events
        return events
            .mapNotNull { event ->
                val distance = distanceMetersOrNull(event, userLocation) ?: return@mapNotNull null
                if (distance <= max) event to distance else null
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * The [events] annotated with distance, filtered/sorted exactly as [apply]
     * would, so a row can show "N km away" without recomputing. The distance is
     * null only under [DistanceBand.ALL] with no location or a coordinate-less
     * event; within a distance band every returned row has a distance.
     */
    fun withDistances(
        events: List<EventSummary>,
        userLocation: LatLng?,
        band: DistanceBand,
    ): List<EventWithDistance> {
        val max = band.maxMeters
        if (max == null || userLocation == null) {
            return events.map { EventWithDistance(it, distanceMetersOrNull(it, userLocation)) }
        }
        return events
            .mapNotNull { event ->
                val distance = distanceMetersOrNull(event, userLocation) ?: return@mapNotNull null
                if (distance <= max) EventWithDistance(event, distance) else null
            }
            .sortedBy { it.distanceMeters }
    }
}
