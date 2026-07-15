package com.kungsbackacarcommunity.app.chatchannels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a channel send. */
sealed interface ChannelSendStatus {
    data object Idle : ChannelSendStatus

    data object Sending : ChannelSendStatus

    data class Failed(val error: ChannelSendError) : ChannelSendStatus
}

/** Older-page pagination status for a channel. */
sealed interface ChannelPageStatus {
    data object Idle : ChannelPageStatus

    data object Loading : ChannelPageStatus

    /** Reached the beginning of the channel — no older pages. */
    data object End : ChannelPageStatus

    /** The last older-page load failed transiently. NOT terminal — retryable. */
    data object Error : ChannelPageStatus
}

/**
 * Orchestrates one open channel (community OR convoy): send status, older-page
 * pagination (accumulated off the live newest-window), and an optional mark-read
 * (community only). Pure Kotlin (no Firebase/Android types) so it is
 * unit-testable with fakes; the live message stream itself is collected in the
 * route. Mirrors dm/DmThreadCoordinator, generalized over the two channel kinds
 * via injected [sender]/[pager]/[marker] lambdas so community and convoy share
 * one coordinator instead of duplicating this state machine.
 *
 * [sentCount] increments on every successful send; unused for re-subscription
 * (channel docs already exist), but kept for parity + tests.
 */
class ChannelChatCoordinator(
    private val sender: suspend (String) -> ChannelSendResult,
    private val pager: suspend (String) -> ChannelOlderResult,
    private val marker: (suspend () -> Unit)? = null,
) {
    private val sendState = MutableStateFlow<ChannelSendStatus>(ChannelSendStatus.Idle)
    val sendStatus: StateFlow<ChannelSendStatus> = sendState.asStateFlow()

    private val older = MutableStateFlow<List<ChannelMessage>>(emptyList())
    val olderMessages: StateFlow<List<ChannelMessage>> = older.asStateFlow()

    private val page = MutableStateFlow<ChannelPageStatus>(ChannelPageStatus.Idle)
    val pageStatus: StateFlow<ChannelPageStatus> = page.asStateFlow()

    private val sent = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = sent.asStateFlow()

    suspend fun send(text: String) {
        if (sendState.value == ChannelSendStatus.Sending) return
        if (!ChannelThread.isSendable(text)) return
        sendState.value = ChannelSendStatus.Sending
        try {
            when (val result = sender(text.trim())) {
                is ChannelSendResult.Sent -> {
                    sendState.value = ChannelSendStatus.Idle
                    sent.value += 1
                }
                is ChannelSendResult.Failed ->
                    sendState.value = ChannelSendStatus.Failed(result.error)
            }
        } catch (cancellation: CancellationException) {
            sendState.value = ChannelSendStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            sendState.value = ChannelSendStatus.Failed(ChannelSendError.Generic)
        }
    }

    /** Marks the channel read (community only — no-op when [marker] is null). Best-effort. */
    suspend fun markRead() {
        val mark = marker ?: return
        try {
            mark()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Idempotent bookkeeping; a transient failure is fine.
        }
    }

    /**
     * Loads the page of messages older than [beforeIso] and accumulates them.
     * No-op when there is no cursor, a page is already loading, or the beginning
     * has been reached. A previous [ChannelPageStatus.Error] is retryable.
     *
     * A transient failure ends in [ChannelPageStatus.Error] (retryable); ONLY a
     * genuine end-of-pagination (`hasMore == false`) ends in
     * [ChannelPageStatus.End].
     */
    suspend fun loadOlder(beforeIso: String?) {
        if (beforeIso == null) return
        if (page.value == ChannelPageStatus.Loading || page.value == ChannelPageStatus.End) return
        page.value = ChannelPageStatus.Loading
        try {
            when (val result = pager(beforeIso)) {
                is ChannelOlderResult.Loaded -> {
                    older.value = ChannelThread.merge(older.value, result.page.messages)
                    page.value =
                        if (result.page.hasMore) ChannelPageStatus.Idle else ChannelPageStatus.End
                }
                ChannelOlderResult.Failed -> page.value = ChannelPageStatus.Error
            }
        } catch (cancellation: CancellationException) {
            page.value = ChannelPageStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            page.value = ChannelPageStatus.Error
        }
    }

    /** Clears a send failure so the input is usable again (e.g. on edit). */
    fun resetSendError() {
        if (sendState.value is ChannelSendStatus.Failed) sendState.value = ChannelSendStatus.Idle
    }
}
