package com.kungsbackacarcommunity.app.dm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of a DM send. */
sealed interface DmSendStatus {
    data object Idle : DmSendStatus

    data object Sending : DmSendStatus

    data class Failed(val error: DmSendError) : DmSendStatus
}

/** Older-page pagination status for a thread. */
sealed interface DmPageStatus {
    /** Older pages may exist and none is currently loading. */
    data object Idle : DmPageStatus

    data object Loading : DmPageStatus

    /** Reached the beginning of the conversation — no older pages. */
    data object End : DmPageStatus

    /**
     * The last older-page load failed transiently. NOT terminal: the "load
     * older" affordance stays visible so the user can retry (unlike [End]).
     */
    data object Error : DmPageStatus
}

/**
 * Orchestrates one open DM thread: send status, older-page pagination
 * (accumulated off the live newest-window), and mark-read. Pure Kotlin (no
 * Firebase/Android types) so it is unit-testable with a fake repository. The
 * live message stream itself is collected in the route (a Firestore listener);
 * this coordinator owns only the imperative actions and their state.
 *
 * [sentCount] increments on every successful send; the route watches it to
 * re-subscribe the message listener the first time a send creates the (until
 * then non-existent) conversation document.
 */
class DmThreadCoordinator(
    private val repository: DmRepository,
    private val otherUid: String,
    private val conversationId: String,
) {
    private val sendState = MutableStateFlow<DmSendStatus>(DmSendStatus.Idle)
    val sendStatus: StateFlow<DmSendStatus> = sendState.asStateFlow()

    private val older = MutableStateFlow<List<DmMessage>>(emptyList())
    val olderMessages: StateFlow<List<DmMessage>> = older.asStateFlow()

    private val page = MutableStateFlow<DmPageStatus>(DmPageStatus.Idle)
    val pageStatus: StateFlow<DmPageStatus> = page.asStateFlow()

    private val sent = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = sent.asStateFlow()

    suspend fun send(text: String) {
        if (sendState.value == DmSendStatus.Sending) return
        if (!DmThread.isSendable(text)) return
        sendState.value = DmSendStatus.Sending
        try {
            when (val result = repository.sendMessage(otherUid, text.trim())) {
                is DmSendResult.Sent -> {
                    sendState.value = DmSendStatus.Idle
                    sent.value += 1
                }
                is DmSendResult.Failed -> sendState.value = DmSendStatus.Failed(result.error)
            }
        } catch (cancellation: CancellationException) {
            sendState.value = DmSendStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            sendState.value = DmSendStatus.Failed(DmSendError.Generic)
        }
    }

    /** Marks the conversation read. Idempotent and best-effort (failures are swallowed). */
    suspend fun markRead() {
        try {
            repository.markRead(conversationId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Idempotent bookkeeping; a not-found/transient failure is fine.
        }
    }

    /**
     * Marks the conversation read only when [newest] is an INCOMING message
     * (sent by the other party). A newest message that is the caller's OWN send
     * carries no unread to clear, so this skips the callable — avoiding a
     * needless `dm-markRead` invocation on every self-send.
     */
    suspend fun markReadIfIncoming(newest: DmMessage?) {
        if (newest != null && newest.senderUid == otherUid) markRead()
    }

    /**
     * Loads the page of messages older than [beforeIso] and accumulates them.
     * No-op when there is no cursor, a page is already loading, or the beginning
     * has been reached ([DmPageStatus.End]). A previous [DmPageStatus.Error] is
     * retryable, so it does NOT block a fresh attempt.
     *
     * A transient failure ends in [DmPageStatus.Error] (retryable), and ONLY a
     * genuine end-of-pagination (`hasMore == false` from the backend) ends in
     * [DmPageStatus.End] — so a transient error can never permanently hide the
     * "load older" affordance.
     */
    suspend fun loadOlder(beforeIso: String?) {
        if (beforeIso == null) return
        if (page.value == DmPageStatus.Loading || page.value == DmPageStatus.End) return
        page.value = DmPageStatus.Loading
        try {
            when (val result = repository.loadOlder(conversationId, beforeIso)) {
                is DmOlderResult.Loaded -> {
                    older.value = DmThread.merge(older.value, result.page.messages)
                    page.value = if (result.page.hasMore) DmPageStatus.Idle else DmPageStatus.End
                }
                // Transient failure: surface a retryable error, NOT End.
                DmOlderResult.Failed -> page.value = DmPageStatus.Error
            }
        } catch (cancellation: CancellationException) {
            page.value = DmPageStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            // An unexpected throw is transient too — stay retryable, not End.
            page.value = DmPageStatus.Error
        }
    }

    /** Clears a send failure so the input is usable again (e.g. on edit). */
    fun resetSendError() {
        if (sendState.value is DmSendStatus.Failed) sendState.value = DmSendStatus.Idle
    }
}
