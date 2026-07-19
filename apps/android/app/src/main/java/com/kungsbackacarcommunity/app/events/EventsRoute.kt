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
import com.kungsbackacarcommunity.app.blocking.BlockedUsersState
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.chat.EventChatRepository
import com.kungsbackacarcommunity.app.chat.EventChatRoute
import com.kungsbackacarcommunity.app.groupdrive.GroupDrive
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveCoordinator
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveRepository
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveRoute
import kotlinx.coroutines.flow.first
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
    passesMemberGate: Boolean,
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
    // Opens a member's read-only profile (the shell's MemberProfile route —
    // reused, not rebuilt). Two member-bearing surfaces share it: the event
    // chat's message authors, and the attendee list's rows. Null in a build
    // with no member-profile repository, which leaves both inert rather than
    // navigating into a route that could only render a permanent spinner.
    onViewProfile: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showGroupDrive by rememberSaveable { mutableStateOf(false) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    // Upcoming vs past. Saveable so a rotation does not silently drop the user
    // back onto the upcoming tab while they are reading the archive.
    var listTab by rememberSaveable { mutableStateOf(EventsListTab.UPCOMING) }
    // Bumped by the "try again" affordance to re-subscribe the observe flows.
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    val selected = selectedEventId

    // Owns the create-event write status; built here (a thin wrapper over the
    // repository) so AuthenticatedApp does not have to thread another coordinator.
    val createCoordinator = remember(repository) { CreateEventCoordinator(repository) }

    // System/gesture Back unwinds one internal level (chat/group-drive -> detail,
    // detail -> list); at the list root it is disabled so the shell's BackHandler
    // returns to Home. Mirrors the in-screen Back buttons' reset behaviour.
    BackHandler(enabled = selected != null || showCreate) {
        when {
            showCreate -> {
                showCreate = false
                createCoordinator.reset()
            }
            showChat -> showChat = false
            showGroupDrive -> showGroupDrive = false
            else -> {
                selectedEventId = null
                rsvpCoordinator?.reset()
            }
        }
    }

    if (selected == null) {
        if (showCreate) {
            val createStatus by
                createCoordinator.status.collectAsState(initial = CreateEventStatusUi.Idle)

            // On a successful create, surface a snackbar and return to the list.
            // A member's event is published on creation (post-moderated), so it
            // appears in the published-only list straight away — no draft wait.
            val snackbarHostState = LocalSnackbarHostState.current
            val createdMessage = stringResource(R.string.events_createSuccess)
            LaunchedEffect(createStatus, snackbarHostState, createdMessage) {
                if (createStatus is CreateEventStatusUi.Success) {
                    snackbarHostState?.showSnackbar(createdMessage)
                    showCreate = false
                    createCoordinator.reset()
                }
            }

            CreateEventScreen(
                status = createStatus,
                onSubmit = { input -> scope.launch { createCoordinator.submit(input) } },
                onCancel = {
                    showCreate = false
                    createCoordinator.reset()
                },
            )
            return
        }

        // One tab is subscribed at a time: `remember(listTab, ...)` tears the
        // previous listener down when the tab flips, so browsing the archive
        // does not leave the upcoming query attached (and vice versa).
        val listState by
            remember(repository, reloadKey, listTab) {
                when (listTab) {
                    EventsListTab.UPCOMING -> repository.observePublishedEvents()
                    EventsListTab.PAST -> repository.observePastEvents()
                }
            }
                .collectAsState(initial = EventsListState.Loading)
        EventsListScreen(
            state = listState,
            onOpenEvent = { selectedEventId = it },
            tab = listTab,
            onSelectTab = { listTab = it },
            onRetry = { reloadKey++ },
            onBack = onBack,
            onCreateEvent = { showCreate = true },
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
            passesMemberGate = passesMemberGate,
            eventStatus = event?.status,
            myRsvp = myRsvp,
            onBack = { showChat = false },
            blockingRepository = blockingRepository,
            onViewProfile = onViewProfile,
        )
        return
    }

    if (showGroupDrive && groupDriveRepository != null) {
        GroupDriveRoute(
            repository = groupDriveRepository,
            coordinator = groupDriveCoordinator,
            eventId = selected,
            uid = uid,
            passesMemberGate = passesMemberGate,
            eventStatus = event?.status,
            myRsvp = myRsvp,
            onShowOnMap = onShowOnMap,
            onBack = { showGroupDrive = false },
        )
        return
    }

    val detail by
        remember(selected, passesMemberGate) {
            if (passesMemberGate) repository.observeEventDetail(selected) else flowOf(null)
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

    // Who's going. Loaded once per (event, reload) and folded through the
    // viewer's block list: someone the viewer blocked is invisible here just as
    // they are in chat and on profiles. The count rendered beside the list
    // stays the server's public tally — see EventAttendees.stateFor.
    val attendeesVisible = Events.canSeeDetails(passesMemberGate, event?.status ?: EventStatus.DRAFT)
    var attendeesReloadKey by rememberSaveable { mutableStateOf(0) }
    var attendees by remember(selected) {
        mutableStateOf<EventAttendeesState>(EventAttendeesState.Loading)
    }
    LaunchedEffect(selected, attendeesVisible, attendeesReloadKey, blockingRepository, uid) {
        if (!attendeesVisible) return@LaunchedEffect
        attendees = EventAttendeesState.Loading
        val result = repository.loadAttendees(selected)
        // Only a Loaded roster has rows to filter, and stateFor ignores the
        // block list on every other branch (see EventAttendeesTest's
        // "blocked uids are irrelevant unless the roster loaded"). Skipping the
        // fetch matters because Unavailable is the COMMON path — the roster read
        // is denied for a normal member today — and waiting on a block-list read
        // there would hold the section on Loading for a result it cannot use.
        val blocked =
            if (result is EventAttendeesResult.Loaded) {
                blockedUids(blockingRepository, uid)
            } else {
                emptySet()
            }
        attendees = EventAttendees.stateFor(result, blocked)
    }

    val chatEligible =
        chatEnabled &&
            chatRepository != null &&
            chatCoordinator != null &&
            EventChat.canParticipate(passesMemberGate, event?.status, myRsvp)
    val groupDriveEligible =
        groupDriveRepository != null &&
            GroupDrive.canJoin(passesMemberGate, event?.status, myRsvp)

    EventDetailScreen(
        event = event,
        detail = detail,
        myRsvp = myRsvp,
        passesMemberGate = passesMemberGate,
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
        attendees = attendees,
        onOpenMember = onViewProfile,
        onRetryAttendees = { attendeesReloadKey++ },
    )
}

/**
 * The uids [viewerUid] has blocked, read from their own owner-scoped block
 * list. Best-effort and deliberately mirrors MemberProfileRoute.hasBlocked: a
 * null repository, a blank uid, or a settled non-Loaded state resolves to "no
 * blocks" — the attendee read is rules-protected either way, and failing the
 * whole roster because the block list hiccuped would be a worse trade.
 *
 * This only ever reflects who the VIEWER blocked, never who blocked them.
 */
private suspend fun blockedUids(
    blockingRepository: BlockingRepository?,
    viewerUid: String,
): Set<String> {
    if (blockingRepository == null || viewerUid.isBlank()) return emptySet()
    val settled =
        blockingRepository.observeBlocked(viewerUid).first { it !is BlockedUsersState.Loading }
    return if (settled is BlockedUsersState.Loaded) {
        settled.users.map { it.userId }.toSet()
    } else {
        emptySet()
    }
}
