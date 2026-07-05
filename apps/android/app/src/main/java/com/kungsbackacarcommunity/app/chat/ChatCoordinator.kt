package com.kungsbackacarcommunity.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a chat post. */
sealed interface ChatSendStatus {
    data object Idle : ChatSendStatus

    data object Sending : ChatSendStatus

    data object Failed : ChatSendStatus
}

/** UI-facing status of a message report. */
sealed interface ChatReportStatus {
    data object Idle : ChatReportStatus

    data object Reporting : ChatReportStatus

    data object Done : ChatReportStatus

    data object Failed : ChatReportStatus
}

/**
 * Orchestrates chat posts and reports (Phase 12 slice 10). Pure Kotlin (no
 * Firebase/Android types) so it is unit-testable with a fake repository. Post
 * and report track separate status flows so a report never blocks sending.
 */
class ChatCoordinator(
    private val repository: EventChatRepository,
) {
    private val send = MutableStateFlow<ChatSendStatus>(ChatSendStatus.Idle)
    val sendStatus: StateFlow<ChatSendStatus> = send.asStateFlow()

    private val report = MutableStateFlow<ChatReportStatus>(ChatReportStatus.Idle)
    val reportStatus: StateFlow<ChatReportStatus> = report.asStateFlow()

    suspend fun post(eventId: String, message: String) {
        if (send.value == ChatSendStatus.Sending) return
        if (!EventChat.isSendable(message)) return
        send.value = ChatSendStatus.Sending
        try {
            repository.postMessage(eventId, message)
            send.value = ChatSendStatus.Idle
        } catch (cancellation: CancellationException) {
            send.value = ChatSendStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            send.value = ChatSendStatus.Failed
        }
    }

    suspend fun submitReport(
        eventId: String,
        messageId: String,
        reason: ChatReportReason,
        details: String? = null,
    ) {
        if (report.value == ChatReportStatus.Reporting) return
        report.value = ChatReportStatus.Reporting
        try {
            repository.reportMessage(eventId, messageId, reason, details)
            report.value = ChatReportStatus.Done
        } catch (cancellation: CancellationException) {
            report.value = ChatReportStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            report.value = ChatReportStatus.Failed
        }
    }

    /** Clears a send failure so the input is usable again. */
    fun resetSend() {
        if (send.value == ChatSendStatus.Failed) send.value = ChatSendStatus.Idle
    }

    /** Clears the report status back to Idle (after showing Done/Failed). */
    fun resetReport() {
        report.value = ChatReportStatus.Idle
    }
}
