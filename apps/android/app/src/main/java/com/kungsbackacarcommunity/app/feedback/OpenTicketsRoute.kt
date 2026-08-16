package com.kungsbackacarcommunity.app.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * "Open tickets" browser route. Builds its own Firestore/callable-backed
 * repository from context (guarded — null in config-less builds) and a pure
 * [OpenTicketsCoordinator] for the optimistic interaction state, collects the
 * live openTickets stream, and wires the browser-open through the SHARED
 * [openGitHubUrl] helper (the same github.com-only guard the report success
 * window uses).
 *
 * Reached only while the `reportTicketsBrowser` flag is on (the caller gates the
 * navigation entry), so no flag check is needed here. Back is driven by the
 * shell's system-Back (ShellBackResult.CloseRoute), so no explicit back param.
 */
@Composable
fun OpenTicketsRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { FirebaseOpenTicketsRepository.createIfAvailable(context) }
    val coordinator = remember(repository) { repository?.let { OpenTicketsCoordinator(it) } }

    val listFlow =
        remember(repository) {
            repository?.observe() ?: MutableStateFlow<OpenTicketsListState>(OpenTicketsListState.Error)
        }
    val listState by listFlow.collectAsState(initial = OpenTicketsListState.Loading)
    val interactions by
        (coordinator?.interactions ?: flowOf(emptyMap<Int, TicketInteractionState>()))
            .collectAsState(initial = emptyMap())

    OpenTicketsScreen(
        listState = listState,
        interactions = interactions,
        onPlusOne = { number -> coordinator?.let { c -> scope.launch { c.plusOne(number) } } },
        onComment = { number, text -> coordinator?.let { c -> scope.launch { c.comment(number, text) } } },
        onCommentEdited = { number -> coordinator?.clearError(number) },
        onOpenInGitHub = { url -> openGitHubUrl(context, url) },
    )
}
