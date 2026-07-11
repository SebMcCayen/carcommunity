package com.kungsbackacarcommunity.app.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.shell.AeroPage
import java.text.DateFormat
import java.util.Date

/**
 * Published-events list (Phase 12 slice 9). Stateless: renders the given
 * [state] and reports taps. Any authenticated user sees the teaser list;
 * member-gated detail lives on [EventDetailScreen].
 */
@Composable
fun EventsListScreen(
    state: EventsListState,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    // Re-invokes the list load; when null the error state shows no retry.
    onRetry: (() -> Unit)? = null,
) {
    AeroPage(title = stringResource(R.string.events_title), modifier = modifier) {
            Text(
                text = stringResource(R.string.events_screenSubtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                        EmptyState()
                    } else {
                        state.events.forEach { event ->
                            EventCard(event = event, onClick = { onOpenEvent(event.id) })
                        }
                    }
            }
    }
}

@Composable
private fun EmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.events_noUpcomingTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.events_noUpcomingBody),
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
