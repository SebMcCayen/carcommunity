package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kungsbackacarcommunity.app.blocking.BlockActionStatus
import com.kungsbackacarcommunity.app.blocking.BlockedUsersState
import com.kungsbackacarcommunity.app.blocking.BlockingCoordinator
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Event-chat integration route (Phase 12 slice 10): wires the repository stream
 * and the coordinator into [EventChatScreen]. Kept out of AuthenticatedApp so
 * that composable stays small; the screen is UI-tested directly.
 *
 * Blocking-in-context (Phase 12 follow-up): when [blockingRepository] is wired,
 * the route observes the caller's own blocked list and (a) hides messages whose
 * author the caller has blocked ([EventChat.filterBlocked]) and (b) offers a
 * "block user" action on another user's message. Blocks are directional and
 * never revealed to the target; filtering is client-side display filtering only
 * (server-side enforcement is a separate parity row). When [blockingRepository]
 * is null (config-less builds) there is no block action and no filtering.
 */
@Composable
fun EventChatRoute(
    repository: EventChatRepository,
    coordinator: ChatCoordinator?,
    eventId: String,
    currentUid: String,
    isActiveMember: Boolean,
    eventStatus: EventStatus?,
    myRsvp: RsvpStatus?,
    onBack: () -> Unit,
    blockingRepository: BlockingRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val canParticipate = EventChat.canParticipate(isActiveMember, eventStatus, myRsvp)

    // The ChatCoordinator is shared across events; clear any prior send/report
    // status when the event changes so a stale message doesn't leak into a
    // different event's chat.
    LaunchedEffect(eventId) {
        coordinator?.resetSend()
        coordinator?.resetReport()
    }

    // A per-route coordinator scoped to the blocking repository (or null when
    // blocking is unavailable). Reset when re-entering so a stale Done/Failed
    // banner doesn't carry over.
    val blockingCoordinator =
        remember(blockingRepository) { blockingRepository?.let { BlockingCoordinator(it) } }
    LaunchedEffect(eventId, blockingCoordinator) { blockingCoordinator?.reset() }

    // Combine the live message stream with the caller's blocked set so that
    // blocking someone from chat makes their messages disappear live. When
    // blocking is unavailable the blocked set is always empty (no filtering).
    val blockedFlow =
        remember(blockingRepository, currentUid) {
            blockingRepository?.observeBlocked(currentUid) ?: flowOf(BlockedUsersState.Loaded(emptyList()))
        }
    val messagesState by
        remember(repository, eventId, canParticipate, blockedFlow) {
            // Only subscribe when eligible; the rules would deny it otherwise.
            val messages =
                if (canParticipate) repository.observeMessages(eventId) else flowOf(ChatMessagesState.Loaded(emptyList()))
            combine(messages, blockedFlow) { state, blocked ->
                val blockedUids =
                    (blocked as? BlockedUsersState.Loaded)?.users?.map { it.userId }?.toSet() ?: emptySet()
                when (state) {
                    is ChatMessagesState.Loaded ->
                        ChatMessagesState.Loaded(EventChat.filterBlocked(state.messages, blockedUids))
                    else -> state
                }
            }
        }
            .collectAsState(initial = ChatMessagesState.Loading)
    val sendStatus by
        (coordinator?.sendStatus ?: flowOf(ChatSendStatus.Idle)).collectAsState(initial = ChatSendStatus.Idle)
    val reportStatus by
        (coordinator?.reportStatus ?: flowOf(ChatReportStatus.Idle))
            .collectAsState(initial = ChatReportStatus.Idle)
    val blockStatus by
        (blockingCoordinator?.actionStatus ?: flowOf(BlockActionStatus.Idle))
            .collectAsState(initial = BlockActionStatus.Idle)

    EventChatScreen(
        state = messagesState,
        currentUid = currentUid,
        canParticipate = canParticipate,
        sendStatus = sendStatus,
        reportStatus = reportStatus,
        onSend = { text -> coordinator?.let { c -> scope.launch { c.post(eventId, text) } } },
        onReport = { messageId, reason ->
            coordinator?.let { c -> scope.launch { c.submitReport(eventId, messageId, reason) } }
        },
        onReportDismiss = { coordinator?.resetReport() },
        onBack = onBack,
        canBlock = blockingCoordinator != null,
        blockStatus = blockStatus,
        onBlock = { authorUserId ->
            blockingCoordinator?.let { c ->
                scope.launch {
                    // Clear any prior terminal status before the new block so the
                    // Done/Failed banner reflects this action; the blocked-list
                    // observer then hides the author's messages live on success.
                    c.reset()
                    c.block(authorUserId)
                }
            }
        },
        onBlockDismiss = { blockingCoordinator?.reset() },
    )
}
