package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Event-chat integration route (Phase 12 slice 10): wires the repository stream
 * and the coordinator into [EventChatScreen]. Kept out of AuthenticatedApp so
 * that composable stays small; the screen is UI-tested directly.
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
) {
    val scope = rememberCoroutineScope()
    val canParticipate = EventChat.canParticipate(isActiveMember, eventStatus, myRsvp)

    val messagesState by
        remember(repository, eventId, canParticipate) {
            // Only subscribe when eligible; the rules would deny it otherwise.
            if (canParticipate) repository.observeMessages(eventId) else flowOf(ChatMessagesState.Loaded(emptyList()))
        }
            .collectAsState(initial = ChatMessagesState.Loading)
    val sendStatus by
        (coordinator?.sendStatus ?: flowOf(ChatSendStatus.Idle)).collectAsState(initial = ChatSendStatus.Idle)
    val reportStatus by
        (coordinator?.reportStatus ?: flowOf(ChatReportStatus.Idle))
            .collectAsState(initial = ChatReportStatus.Idle)

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
    )
}
