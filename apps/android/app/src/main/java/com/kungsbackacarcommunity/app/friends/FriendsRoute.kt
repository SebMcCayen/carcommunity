package com.kungsbackacarcommunity.app.friends

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import com.kungsbackacarcommunity.app.diagnostics.FirebaseClientErrorReporter
import com.kungsbackacarcommunity.app.usersearch.FirebaseUserSearchRepository
import com.kungsbackacarcommunity.app.usersearch.UserSearchCoordinator
import com.kungsbackacarcommunity.app.usersearch.UserSearchRepository
import com.kungsbackacarcommunity.app.usersearch.UserSearchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Friends integration route: builds the coordinator, loads the snapshot on
 * entry (there is no live listener — the callable graph is re-fetched after each
 * mutation), and drives [FriendsScreen] via coordinator actions.
 *
 * [errorReporter] is threaded into the coordinator so an UNCLASSIFIED friends
 * failure — the "Something went wrong" case — files a deduplicated GitHub issue
 * via the shared `errors-reportClientError` pipeline instead of vanishing.
 * Defaulted here (rather than threaded from the caller) so tests can inject a
 * fake; a config-less build gets null and simply skips reporting.
 *
 * [searchRepository] backs the member typeahead. It is built here from the local
 * context — the same guarded pattern as [errorReporter], rather than threaded
 * down through the app's already very long repository parameter lists — and is
 * INDEPENDENTLY nullable: a config-less build still gets the full friends
 * surface, with the search field simply OMITTED (not rendered inert). The search
 * is therefore strictly additive, never a new way for this screen to fail to
 * render.
 */
@Composable
fun FriendsRoute(
    repository: FriendsRepository,
    onMessageFriend: (FriendSummary) -> Unit,
    onViewProfile: (FriendSummary) -> Unit,
    onOpenMemberProfile: (String) -> Unit = {},
    searchRepository: UserSearchRepository? = defaultUserSearchRepository(),
    errorReporter: ClientErrorReporter? = defaultClientErrorReporter(),
) {
    val coordinator = remember(repository, errorReporter) {
        FriendsCoordinator(repository, errorReporter)
    }
    val status by coordinator.status.collectAsState()
    val addState by coordinator.add.collectAsState()
    val actionError by coordinator.actionError.collectAsState()
    val busyRows by coordinator.busyRows.collectAsState()
    val scope = rememberCoroutineScope()

    // The search coordinator launches its debounced work into the composition's
    // scope, so leaving the screen cancels any pending or in-flight search along
    // with it — no result can arrive for a screen that is gone.
    val searchCoordinator =
        remember(searchRepository, scope) {
            searchRepository?.let { UserSearchCoordinator(it, scope) }
        }
    var searchQuery by remember { mutableStateOf("") }
    // A constant null flow stands in when there is no search backend, so
    // collectAsState() is called UNCONDITIONALLY. Collecting inside an elvis
    // branch would make the composable call order depend on whether the
    // repository is null, which is exactly what Compose's positional memoization
    // forbids. `StateFlow` is covariant, so the coordinator's
    // StateFlow<UserSearchState> is already a StateFlow<UserSearchState?>.
    //
    // A null state is how the screen knows to OMIT the field entirely rather than
    // render one that accepts typing and can never answer — which would read as
    // "nobody matches" for every query.
    val searchStateFlow: StateFlow<UserSearchState?> =
        remember(searchCoordinator) { searchCoordinator?.state ?: MutableStateFlow(null) }
    val searchState by searchStateFlow.collectAsState()

    LaunchedEffect(coordinator) { coordinator.load() }

    // Drop any results still on screen when the route leaves, so returning to
    // Friends never opens on a stale suggestion list from a previous visit.
    DisposableEffect(searchCoordinator) {
        onDispose { searchCoordinator?.clear() }
    }

    FriendsScreen(
        status = status,
        addState = addState,
        actionError = actionError,
        busyRows = busyRows,
        searchQuery = searchQuery,
        searchState = searchState,
        onSearchQueryChange = { typed ->
            searchQuery = typed
            searchCoordinator?.onQueryChanged(typed)
        },
        onOpenMemberProfile = onOpenMemberProfile,
        onSend = { nickname -> scope.launch { coordinator.sendRequestByNickname(nickname) } },
        onChooseCandidate = { uid -> scope.launch { coordinator.chooseCandidate(uid) } },
        onDismissAdd = { coordinator.resetAdd() },
        onAccept = { requestId -> scope.launch { coordinator.accept(requestId) } },
        onDecline = { requestId -> scope.launch { coordinator.decline(requestId) } },
        onRemove = { friendUid -> scope.launch { coordinator.remove(friendUid) } },
        onClearActionError = { coordinator.clearActionError() },
        onMessageFriend = onMessageFriend,
        onViewProfile = onViewProfile,
    )
}

/**
 * Builds the Firebase-backed [ClientErrorReporter] from the local context, or
 * null in a config-less build (mirrors the Messages route's default).
 */
@Composable
private fun defaultClientErrorReporter(): ClientErrorReporter? {
    val context = LocalContext.current
    return remember(context) { FirebaseClientErrorReporter.createIfAvailable(context) }
}

/**
 * Builds the callable-backed member-search repository from the local context, or
 * null in a config-less build (the search field then stays idle).
 */
@Composable
private fun defaultUserSearchRepository(): UserSearchRepository? {
    val context = LocalContext.current
    return remember(context) { FirebaseUserSearchRepository.createIfAvailable(context) }
}
