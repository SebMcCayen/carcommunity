package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.navigation.LatLng
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Which slice of the events collection the list is showing. */
enum class EventsListTab {
    /** `status == published` — upcoming, soonest first. */
    UPCOMING,

    /** `status == completed` — the archive, most recent first. */
    PAST,
}

/**
 * Events list (Phase 12 slice 9; past/archive tab added alongside
 * `events-autoClose`). Stateless: renders the given [state] for the selected
 * [tab] and reports taps; member-gated detail lives on [EventDetailScreen].
 *
 * Any authenticated user sees the published teaser list. The completed list
 * behind [EventsListTab.PAST] additionally requires the `firestore.rules`
 * change from PR #455 — see [EventsRepository.observePastEvents]. This screen
 * renders whatever [state] it is handed and makes no claim about which reads
 * the rules currently permit.
 *
 * The two tabs are two different queries, so the caller owns [tab] and swaps
 * [state] to match. Creating an event belongs to the upcoming tab only: the
 * button is not rendered on [EventsListTab.PAST] even when [onCreateEvent] is
 * non-null, since "create" has no meaning in an archive.
 *
 * Distance filtering is opt-in: when [onSelectDistanceBand] is non-null a chip
 * row lets the viewer narrow the list to events within [distanceBand] of
 * [userLocation]. The narrowing itself is the pure [EventDistanceFilter] (unit
 * tested), not inline logic here. A null [userLocation] (no permission / no fix)
 * disables the distance chips and shows a hint — "All" is the only honest option
 * with nowhere to measure from.
 */
@Composable
fun EventsListScreen(
    state: EventsListState,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
    tab: EventsListTab = EventsListTab.UPCOMING,
    onSelectTab: ((EventsListTab) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    // Re-invokes the list load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
    // Opens the create-event form; when null the button is hidden.
    onCreateEvent: (() -> Unit)? = null,
    // The viewer's current position (from navigation/CurrentLocation), or null
    // when there is no permission / no fix. Backs the distance filter and the
    // per-row "N km away" label.
    userLocation: LatLng? = null,
    // The selected distance band; ignored unless [onSelectDistanceBand] is set.
    distanceBand: DistanceBand = DistanceBand.ALL,
    // Selecting a distance band; when null the whole filter row is hidden (keeps
    // existing test/preview callers and any config-less caller unchanged).
    onSelectDistanceBand: ((DistanceBand) -> Unit)? = null,
) {
    AeroPage(title = stringResource(R.string.events_title), modifier = modifier) {
            if (onSelectTab != null) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab(
                        selected = tab == EventsListTab.UPCOMING,
                        onClick = { onSelectTab(EventsListTab.UPCOMING) },
                        text = { Text(text = stringResource(R.string.events_tabUpcoming)) },
                    )
                    Tab(
                        selected = tab == EventsListTab.PAST,
                        onClick = { onSelectTab(EventsListTab.PAST) },
                        text = { Text(text = stringResource(R.string.events_tabPast)) },
                    )
                }
            }

            Text(
                text =
                    stringResource(
                        when (tab) {
                            EventsListTab.UPCOMING -> R.string.events_screenSubtitle
                            EventsListTab.PAST -> R.string.events_pastSubtitle
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onCreateEvent != null && tab == EventsListTab.UPCOMING) {
                Button(onClick = onCreateEvent, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.events_createButton))
                }
            }

            if (onSelectDistanceBand != null) {
                DistanceFilterRow(
                    selected = distanceBand,
                    hasLocation = userLocation != null,
                    onSelect = onSelectDistanceBand,
                )
            }

            when (state) {
                EventsListState.Loading ->
                    Text(
                        text = stringResource(R.string.events_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                is EventsListState.Error -> {
                    Text(
                        text = stringResource(R.string.events_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(text = stringResource(R.string.events_retry))
                        }
                    }
                }

                is EventsListState.Loaded -> {
                    // Pure filter (unit tested); ALL / no-location just pass the
                    // list through, so the rows stay in their upstream order.
                    val rows = EventDistanceFilter.withDistances(state.events, userLocation, distanceBand)
                    if (rows.isEmpty()) {
                        if (state.events.isEmpty()) {
                            EmptyState(tab)
                        } else {
                            // The list is non-empty but nothing survives the band:
                            // an honest "no events near you", not a silent blank.
                            NoNearbyState()
                        }
                    } else {
                        rows.forEach { row ->
                            EventCard(
                                event = row.event,
                                distanceMeters = row.distanceMeters,
                                onClick = { onOpenEvent(row.event.id) },
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun DistanceFilterRow(
    selected: DistanceBand,
    hasLocation: Boolean,
    onSelect: (DistanceBand) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s1)) {
        Text(
            text = stringResource(R.string.events_distanceFilterLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            DistanceBand.values().forEach { band ->
                FilterChip(
                    // A distance band needs a location to measure from; without a
                    // fix only "All" is selectable. "All" is always enabled.
                    enabled = hasLocation || band == DistanceBand.ALL,
                    selected = selected == band,
                    onClick = { onSelect(band) },
                    label = { Text(text = stringResource(distanceBandLabel(band))) },
                )
            }
        }
        if (!hasLocation) {
            Text(
                text = stringResource(R.string.events_distanceFilterNoLocationHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun distanceBandLabel(band: DistanceBand): Int =
    when (band) {
        DistanceBand.ALL -> R.string.events_distanceFilterAll
        DistanceBand.WITHIN_5_KM -> R.string.events_distanceFilterWithin5
        DistanceBand.WITHIN_25_KM -> R.string.events_distanceFilterWithin25
        DistanceBand.WITHIN_50_KM -> R.string.events_distanceFilterWithin50
    }

@Composable
private fun NoNearbyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Text(
                text = stringResource(R.string.events_noNearbyTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.events_noNearbyBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(tab: EventsListTab) {
    val (title, body) =
        when (tab) {
            EventsListTab.UPCOMING -> R.string.events_noUpcomingTitle to R.string.events_noUpcomingBody
            EventsListTab.PAST -> R.string.events_noPastTitle to R.string.events_noPastBody
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventCard(event: EventSummary, distanceMeters: Double?, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            // Title on the left, the "going" tally on the trailing/right edge.
            // The count is the RSVP `going` tally (people who marked themselves
            // as attending), NOT the geofenced check-in count — it reads off the
            // teaser's denormalized rsvpCounts.going maintained by
            // events-onRsvpWrite, so it is public and needs no member gate.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.events_rowGoingCount, event.counts.going),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.isOfficial) {
                Text(
                    text = stringResource(R.string.events_officialBadge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (event.status == EventStatus.CANCELLED) {
                Text(
                    text = stringResource(R.string.events_cancelledBadge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // "Held", not an error colour: a completed event ran as planned.
            // Cancelled and completed are mutually exclusive statuses, so at
            // most one badge shows.
            if (event.status == EventStatus.COMPLETED) {
                Text(
                    text = stringResource(R.string.events_completedBadge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            event.startsAtMillis?.let { millis ->
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            event.approximateArea?.takeIf { it.isNotBlank() }?.let { area ->
                Text(
                    text = area,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Nice-to-have: how far the event is, when a location is available.
            distanceMeters?.let { meters ->
                Text(
                    text = stringResource(R.string.events_distanceRowAway, formatKm(meters)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Metres → a one-decimal kilometre string in the device locale (e.g. "4.2"). */
private fun formatKm(meters: Double): String =
    String.format(Locale.getDefault(), "%.1f", meters / 1_000.0)

@Preview(name = "Events – list", showBackground = true)
@Composable
private fun EventsListPreview() {
    KccTheme {
        EventsListScreen(
            state =
                EventsListState.Loaded(
                    listOf(
                        EventSummary(
                            id = "e1",
                            title = "Kungsbacka Cars & Coffee",
                            summary = null,
                            startsAtMillis = 0L,
                            endsAtMillis = null,
                            approximateArea = "Kungsbacka",
                            isOfficial = true,
                            status = EventStatus.PUBLISHED,
                            counts = RsvpCounts(12, 3, 1),
                        ),
                    ),
                ),
            onOpenEvent = {},
        )
    }
}
