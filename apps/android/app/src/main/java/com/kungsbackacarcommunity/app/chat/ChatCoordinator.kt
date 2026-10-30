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
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val send = MutableStateFlow<ChatSendStatus>(ChatSendStatus.Idle)
    val sendStatus: StateFlow<ChatSendStatus> = send.asStateFlow()

    private val report = MutableStateFlow<ChatReportStatus>(ChatReportStatus.Idle)
    val reportStatus: StateFlow<ChatReportStatus> = report.asStateFlow()

    private val localCooldown = ChatSendCooldown()
    private var retryAttempt: Triple<String, String, String>? = null
    private var retryOwner: ChatSendCooldown? = null

    suspend fun post(eventId: String, message: String, cooldown: ChatSendCooldown = localCooldown, clientId: String? = null): Boolean {
        if (send.value == ChatSendStatus.Sending || cooldown.isBlocked()) return false
        if (!EventChat.isSendable(message)) return false
        if (retryOwner !== cooldown) { retryAttempt = null; retryOwner = cooldown }
        val text = message.trim()
        val id = clientId ?: retryAttempt?.takeIf { it.first == eventId && it.second == text }?.third ?: idGenerator()
        retryAttempt = Triple(eventId, text, id)
        send.value = ChatSendStatus.Sending
        try {
            repository.postMessage(eventId, message, id)
            retryAttempt = null
            send.value = ChatSendStatus.Idle
            return true
        } catch (cancellation: CancellationException) {
            send.value = ChatSendStatus.Idle
            throw cancellation
        } catch (failure: Exception) {
            (failure as? ChatRateLimitedException)?.rateLimit?.let(cooldown::apply)
            send.value = ChatSendStatus.Failed
            return false
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
