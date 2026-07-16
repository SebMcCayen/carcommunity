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
    private val sender: suspend (String, List<String>) -> ChannelSendResult,
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

    private val dropped = MutableStateFlow(0)

    /**
     * How many of the last send's @mentions the server DROPPED — the composer's
     * optimistic picks reconciled against the accepted set the `*-post` response
     * echoed back. Nonzero means a member the user deliberately named was not
     * notified (they deleted their account, lost their subscription, or blocked
     * the sender between picking and posting), which is worth one quiet line;
     * the message itself was still posted. Self-mentions and duplicates cannot
     * land here — the picker excludes the caller and the draft dedupes — so every
     * drop counted is a real one. Cleared by [dismissDroppedMentions].
     */
    val droppedMentionCount: StateFlow<Int> = dropped.asStateFlow()

    suspend fun send(text: String, mentionedUids: List<String> = emptyList()) {
        if (sendState.value == ChannelSendStatus.Sending) return
        if (!ChannelThread.isSendable(text)) return
        sendState.value = ChannelSendStatus.Sending
        dropped.value = 0
        try {
            when (val result = sender(text.trim(), mentionedUids)) {
                is ChannelSendResult.Sent -> {
                    sendState.value = ChannelSendStatus.Idle
                    sent.value += 1
                    // Reconcile: the accepted set is authoritative, and may be a
                    // strict subset of what we sent.
                    val accepted = result.mentionedUids.toSet()
                    dropped.value = mentionedUids.distinct().count { it !in accepted }
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

    /** Dismisses the dropped-mention note (e.g. once the user edits a new draft). */
    fun dismissDroppedMentions() {
        dropped.value = 0
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
