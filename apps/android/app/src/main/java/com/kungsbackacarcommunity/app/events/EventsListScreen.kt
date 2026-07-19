package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date

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

            when (state) {
                EventsListState.Loading ->
                    Text(
                        text = stringResource(R.string.events_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                EventsListState.Error -> {
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

                is EventsListState.Loaded ->
                    if (state.events.isEmpty()) {
                        EmptyState(tab)
                    } else {
                        state.events.forEach { event ->
                            EventCard(event = event, onClick = { onOpenEvent(event.id) })
                        }
                    }
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
private fun EventCard(event: EventSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
            Text(
                text = event.approximateArea,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
