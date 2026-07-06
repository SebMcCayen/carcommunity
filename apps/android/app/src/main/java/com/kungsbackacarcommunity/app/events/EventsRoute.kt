package com.kungsbackacarcommunity.app.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Loading vs loaded (null = missing/unreadable) for a single event's teaser. */
private sealed interface EventLoad {
    data object Loading : EventLoad

    data class Loaded(val event: EventSummary?) : EventLoad
}

/**
 * Events integration route (Phase 12 slice 9): owns the list↔detail selection
 * and wires the repository flows into the two stateless screens. Kept out of
 * [com.kungsbackacarcommunity.app.AuthenticatedApp] so that composable stays
 * small; the screens themselves are UI-tested directly.
 */
@Composable
fun EventsRoute(
    repository: EventsRepository,
    rsvpCoordinator: RsvpCoordinator?,
    uid: String,
    isActiveMember: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedEventId

    if (selected == null) {
        val listState by
            remember(repository) { repository.observePublishedEvents() }
                .collectAsState(initial = EventsListState.Loading)
        EventsListScreen(
            state = listState,
            onOpenEvent = { selectedEventId = it },
            onBack = onBack,
        )
    } else {
        // Track the first snapshot so a null event reads as "loading" (not
        // "error") on the initial composition.
        val eventLoad by
            remember(selected) {
                repository.observeEvent(selected).map<EventSummary?, EventLoad> { EventLoad.Loaded(it) }
            }
                .collectAsState(initial = EventLoad.Loading)
        val event = (eventLoad as? EventLoad.Loaded)?.event
        val eventLoading = eventLoad is EventLoad.Loading
        val detail by
            remember(selected, isActiveMember) {
                if (isActiveMember) repository.observeEventDetail(selected) else flowOf(null)
            }
                .collectAsState(initial = null)
        val myRsvp by
            remember(selected, uid) { repository.observeMyRsvp(selected, uid) }
                .collectAsState(initial = null)
        val rsvpStatus by
            (rsvpCoordinator?.status ?: flowOf(RsvpStatusUi.Idle))
                .collectAsState(initial = RsvpStatusUi.Idle)

        EventDetailScreen(
            event = event,
            detail = detail,
            myRsvp = myRsvp,
            isActiveMember = isActiveMember,
            rsvpStatus = rsvpStatus,
            isLoading = eventLoading,
            onRsvp = { answer ->
                rsvpCoordinator?.let { c -> scope.launch { c.submit(selected, uid, answer) } }
            },
            onBack = {
                selectedEventId = null
                rsvpCoordinator?.reset()
            },
        )
    }
}
