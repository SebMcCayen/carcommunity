package com.kungsbackacarcommunity.app.convoy

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
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendsCoordinator
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsStatus
import kotlinx.coroutines.launch

/** Which convoy sub-screen the route is currently showing. */
private enum class ConvoyView { List, Create, Detail }

/**
 * Convoy management integration route. Builds the coordinator, loads the
 * snapshot on entry (no live listener — the callable set is re-fetched after
 * each mutation), and hosts the internal list → create → detail navigation as
 * a single [com.kungsbackacarcommunity.app.shell.ShellRoute]. A nested
 * [BackHandler] pops the internal stack (create/detail → list) BEFORE the
 * shell's central handler closes the whole route, so system-Back walks back one
 * sub-screen at a time until the list, then exits to the Social hub.
 *
 * The friend invite-picker reads the SHARED [FriendsRepository] via a
 * [FriendsCoordinator] (friend-list → friends), mirroring the Friends screen —
 * only the caller's friends can be invited (the backend also enforces this).
 *
 * The convoy live-map is a separate surface (built elsewhere) and is
 * deliberately not linked from here; see the driving-mode follow-up.
 */
@Composable
fun ConvoyRoute(
    repository: ConvoyRepository,
    friendsRepository: FriendsRepository?,
) {
    val scope = rememberCoroutineScope()
    val coordinator = remember(repository) { ConvoyCoordinator(repository) }
    val status by coordinator.status.collectAsState()
    val actionError by coordinator.actionError.collectAsState()
    val busyConvoys by coordinator.busyConvoys.collectAsState()
    val createState by coordinator.createState.collectAsState()

    // Friends snapshot for the invite-picker (shared FriendsRepository). Loaded
    // lazily via its own coordinator. A null repository (config-less build) has
    // no snapshot to load, so it resolves to a terminal Error state — which the
    // picker renders as an "unavailable" notice — instead of a permanent Loading
    // spinner that would never resolve.
    val friendsCoordinator =
        remember(friendsRepository) { friendsRepository?.let { FriendsCoordinator(it) } }
    val friendsStatus: FriendsStatus =
        friendsCoordinator?.status?.collectAsState()?.value
            ?: FriendsStatus.Error(FriendActionError.Generic)

    var view by rememberSaveable { mutableStateOf(ConvoyView.List) }
    var detailConvoyId by rememberSaveable { mutableStateOf<String?>(null) }

    // Create-flow local form state (reset each time the picker is opened).
    var title by rememberSaveable { mutableStateOf("") }
    var selectedUids by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(coordinator) { coordinator.load() }

    // Pop internal navigation before the shell closes the route. Disabled on the
    // list root so the shell's handler then returns to the Social hub.
    BackHandler(enabled = view != ConvoyView.List) { view = ConvoyView.List }

    when (view) {
        ConvoyView.List ->
            ConvoyListScreen(
                status = status,
                actionError = actionError,
                busyConvoys = busyConvoys,
                onCreate = {
                    // Fresh form + friends snapshot each time the picker opens.
                    title = ""
                    selectedUids = emptySet()
                    coordinator.resetCreate()
                    friendsCoordinator?.let { c -> scope.launch { c.load() } }
                    view = ConvoyView.Create
                },
                onOpenConvoy = { convoyId ->
                    detailConvoyId = convoyId
                    view = ConvoyView.Detail
                },
                onAccept = { convoyId -> scope.launch { coordinator.accept(convoyId) } },
                onDecline = { convoyId -> scope.launch { coordinator.decline(convoyId) } },
                onClearActionError = { coordinator.clearActionError() },
            )

        ConvoyView.Create ->
            CreateConvoyScreen(
                friendsStatus = friendsStatus,
                createState = createState,
                title = title,
                selectedUids = selectedUids,
                onTitleChange = { title = it },
                onToggleFriend = { uid ->
                    selectedUids =
                        if (uid in selectedUids) selectedUids - uid else selectedUids + uid
                },
                onSubmit = {
                    scope.launch { coordinator.create(selectedUids.toList(), title) }
                },
                onDone = { convoyId ->
                    coordinator.resetCreate()
                    detailConvoyId = convoyId
                    view = ConvoyView.Detail
                },
            )

        ConvoyView.Detail -> {
            val convoy = (status as? ConvoyListStatus.Loaded)?.convoy(detailConvoyId ?: "")
            if (convoy != null) {
                ConvoyDetailScreen(
                    convoy = convoy,
                    working = convoy.convoyId in busyConvoys,
                    actionError = actionError,
                    onStart = { scope.launch { coordinator.start(convoy.convoyId) } },
                    onEnd = { scope.launch { coordinator.end(convoy.convoyId) } },
                    onClearActionError = { coordinator.clearActionError() },
                )
            } else {
                // The convoy fell out of the snapshot (e.g. a concurrent change)
                // or the list is still loading — return to the list rather than
                // render an empty detail. This is a transient state, so show a
                // neutral loading placeholder instead of a fully-wired-looking
                // list whose buttons would be dead until the pop lands.
                LaunchedEffect(detailConvoyId, status) {
                    if (status is ConvoyListStatus.Loaded) view = ConvoyView.List
                }
                ConvoyLoadingScreen()
            }
        }
    }
}
