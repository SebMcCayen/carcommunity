package com.kungsbackacarcommunity.app.events

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.LocalSnackbarHostState
import com.kungsbackacarcommunity.app.chat.ChatCoordinator
import com.kungsbackacarcommunity.app.chat.EventChat
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.chat.EventChatRepository
import com.kungsbackacarcommunity.app.chat.EventChatRoute
import com.kungsbackacarcommunity.app.groupdrive.GroupDrive
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveCoordinator
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveRepository
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveRoute
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Loading vs loaded (null = missing/unreadable) for a single event's teaser. */
private sealed interface EventLoad {
    data object Loading : EventLoad

    data class Loaded(val event: EventSummary?) : EventLoad
}

/**
 * Events integration route (Phase 12 slices 9–10): owns the list ↔ detail ↔
 * chat selection and wires the repository flows into the stateless screens.
 * Kept out of AuthenticatedApp so that composable stays small; the screens
 * themselves are UI-tested directly.
 */
@Composable
fun EventsRoute(
    repository: EventsRepository,
    rsvpCoordinator: RsvpCoordinator?,
    uid: String,
    isActiveMember: Boolean,
    chatRepository: EventChatRepository?,
    chatCoordinator: ChatCoordinator?,
    chatEnabled: Boolean,
    groupDriveRepository: GroupDriveRepository?,
    groupDriveCoordinator: GroupDriveCoordinator?,
    onShowOnMap: ((List<String>) -> Unit)? = null,
    onBack: () -> Unit,
    // Blocking-in-context: null-safe. When null, chat offers no block action
    // and does no blocked-author filtering (config-less builds pass unchanged).
    blockingRepository: BlockingRepository? = null,
) {
    val scope = rememberCoroutineScope()
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showGroupDrive by rememberSaveable { mutableStateOf(false) }
    // Bumped by the "try again" affordance to re-subscribe the observe flows.
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    val selected = selectedEventId

    // System/gesture Back unwinds one internal level (chat/group-drive -> detail,
    // detail -> list); at the list root it is disabled so the shell's BackHandler
    // returns to Home. Mirrors the in-screen Back buttons' reset behaviour.
    BackHandler(enabled = selected != null) {
        when {
            showChat -> showChat = false
            showGroupDrive -> showGroupDrive = false
            else -> {
                selectedEventId = null
                rsvpCoordinator?.reset()
            }
        }
    }

    if (selected == null) {
        val listState by
            remember(repository, reloadKey) { repository.observePublishedEvents() }
                .collectAsState(initial = EventsListState.Loading)
        EventsListScreen(
            state = listState,
            onOpenEvent = { selectedEventId = it },
            onRetry = { reloadKey++ },
            onBack = onBack,
        )
        return
    }

    // Track the first snapshot so a null event reads as "loading" (not "error")
    // on the initial composition.
    val eventLoad by
        remember(repository, selected, reloadKey) {
            repository.observeEvent(selected).map<EventSummary?, EventLoad> { EventLoad.Loaded(it) }
        }
            .collectAsState(initial = EventLoad.Loading)
    val event = (eventLoad as? EventLoad.Loaded)?.event
    val eventLoading = eventLoad is EventLoad.Loading
    val myRsvp by
        remember(selected, uid) { repository.observeMyRsvp(selected, uid) }
            .collectAsState(initial = null)

    if (showChat && chatRepository != null) {
        EventChatRoute(
            repository = chatRepository,
            coordinator = chatCoordinator,
            eventId = selected,
            currentUid = uid,
            isActiveMember = isActiveMember,
            eventStatus = event?.status,
            myRsvp = myRsvp,
            onBack = { showChat = false },
            blockingRepository = blockingRepository,
        )
        return
    }

    if (showGroupDrive && groupDriveRepository != null) {
        GroupDriveRoute(
            repository = groupDriveRepository,
            coordinator = groupDriveCoordinator,
            eventId = selected,
            uid = uid,
            isActiveMember = isActiveMember,
            eventStatus = event?.status,
            myRsvp = myRsvp,
            onShowOnMap = onShowOnMap,
            onBack = { showGroupDrive = false },
        )
        return
    }

    val detail by
        remember(selected, isActiveMember) {
            if (isActiveMember) repository.observeEventDetail(selected) else flowOf(null)
        }
            .collectAsState(initial = null)
    val rsvpStatus by
        (rsvpCoordinator?.status ?: flowOf(RsvpStatusUi.Idle))
            .collectAsState(initial = RsvpStatusUi.Idle)

    // Transient failure surfacing: a failed RSVP write raises a shell snackbar
    // (the shared, non-blocking feedback channel) instead of a persistent line.
    val snackbarHostState = LocalSnackbarHostState.current
    val rsvpFailedMessage = stringResource(R.string.events_rsvpSubmitError)
    LaunchedEffect(rsvpStatus, snackbarHostState, rsvpFailedMessage) {
        if (rsvpStatus == RsvpStatusUi.Failed) {
            // Null when no shell host is attached (previews / isolated tests):
            // skip so the coroutine never hangs waiting on a detached host.
            snackbarHostState?.showSnackbar(rsvpFailedMessage)
        }
    }

    val chatEligible =
        chatEnabled &&
            chatRepository != null &&
            chatCoordinator != null &&
            EventChat.canParticipate(isActiveMember, event?.status, myRsvp)
    val groupDriveEligible =
        groupDriveRepository != null &&
            GroupDrive.canJoin(isActiveMember, event?.status, myRsvp)

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
        onRetry = { reloadKey++ },
        onOpenChat = if (chatEligible) { { showChat = true } } else null,
        onOpenGroupDrive = if (groupDriveEligible) { { showGroupDrive = true } } else null,
    )
}
