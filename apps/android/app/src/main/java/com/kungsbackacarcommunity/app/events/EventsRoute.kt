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
import kotlinx.coroutines.launch

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
        val event by
            remember(selected) { repository.observeEvent(selected) }.collectAsState(initial = null)
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
