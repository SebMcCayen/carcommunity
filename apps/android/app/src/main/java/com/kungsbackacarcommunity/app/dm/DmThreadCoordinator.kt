package com.kungsbackacarcommunity.app.dm

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * Orchestrates one open DM thread: OPTIMISTIC send, older-page pagination
 * (accumulated off the live newest-window), and mark-read. Pure Kotlin (no
 * Firebase/Android types) so it is unit-testable with a fake repository. The
 * live message stream itself is collected in the route (a Firestore listener);
 * this coordinator owns only the imperative actions and their state.
 *
 * Send is optimistic: [send] appends a local "sending" bubble to
 * [pendingMessages] IMMEDIATELY (so the message shows the instant the user taps,
 * with no wait for the `dm-sendMessage` round-trip) and fires the callable in
 * the background. On success the bubble flips to [DmDeliveryState.Sent]; on
 * failure to [DmDeliveryState.Failed] with a [retry] affordance (the user's
 * message is never silently dropped). The route displays
 * [DmThread.mergeWithPending] of the server messages + these bubbles, and the
 * bubble is reconciled away — rendered exactly once — the moment the real
 * document arrives from the listener ([onLiveMessages]), matched by its clientId
 * (which is also the delivered doc's id).
 *
 * Idempotency: each optimistic bubble carries a generated clientId used verbatim
 * as the message doc id, so a [retry] resends the SAME key and the backend
 * writes exactly one message / bumps unread once, however many times it is
 * retried.
 *
 * [sentCount] increments on every successful send; the route watches it to
 * re-subscribe the message listener the first time a send creates the (until
 * then non-existent) conversation document.
 */
class DmThreadCoordinator(
    private val repository: DmRepository,
    private val selfUid: String,
    private val otherUid: String,
    private val conversationId: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val pending = MutableStateFlow<List<DmMessage>>(emptyList())

    /**
     * The caller's in-flight/failed optimistic bubbles, oldest-first. Each is a
     * [DmMessage] whose id == its clientId and whose [DmMessage.deliveryState] is
     * [DmDeliveryState.Sending], [DmDeliveryState.Sent] (acked, awaiting the
     * listener), or [DmDeliveryState.Failed]. Merged into the displayed thread by
     * [DmThread.mergeWithPending] and pruned by [onLiveMessages] once delivered.
     */
    val pendingMessages: StateFlow<List<DmMessage>> = pending.asStateFlow()

    private val older = MutableStateFlow<List<DmMessage>>(emptyList())
    val olderMessages: StateFlow<List<DmMessage>> = older.asStateFlow()

    private val page = MutableStateFlow<DmPageStatus>(DmPageStatus.Idle)
    val pageStatus: StateFlow<DmPageStatus> = page.asStateFlow()

    private val sent = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = sent.asStateFlow()

    /**
     * Optimistically sends [text]. Adds the "sending" bubble synchronously (the
     * first thing this does, so the UI updates before the suspending callable),
     * then dispatches `dm-sendMessage` with a fresh idempotency key.
     */
    suspend fun send(text: String) {
        if (!DmThread.isSendable(text)) return
        val trimmed = text.trim()
        val clientId = idGenerator()
        val optimistic =
            DmMessage(
                id = clientId,
                senderUid = selfUid,
                text = trimmed,
                createdAtMillis = clock(),
                createdAtIso = null,
                clientId = clientId,
                deliveryState = DmDeliveryState.Sending,
            )
        pending.update { it + optimistic }
        dispatch(clientId, trimmed)
    }

    /**
     * Re-attempts a previously [DmDeliveryState.Failed] bubble, resending the
     * SAME clientId so the backend stays exactly-once (no double post). A no-op
     * if the bubble isn't found or isn't in a failed state (e.g. already
     * re-sending), so a double-tap can't fire two resends.
     */
    suspend fun retry(clientId: String) {
        val target =
            pending.value.firstOrNull {
                it.clientId == clientId && it.deliveryState == DmDeliveryState.Failed
            } ?: return
        setState(clientId, DmDeliveryState.Sending)
        dispatch(clientId, target.text)
    }

    private suspend fun dispatch(clientId: String, text: String) {
        try {
            when (repository.sendMessage(otherUid, text, clientId)) {
                is DmSendResult.Sent -> {
                    // Flip to Sent so the "sending" affordance clears on the ack;
                    // the bubble is removed for good once the listener delivers the
                    // real doc ([onLiveMessages]), matched by clientId.
                    setState(clientId, DmDeliveryState.Sent)
                    // Atomic: concurrent optimistic sends each resolve on their own
                    // coroutine, so a plain read-modify-write could drop increments.
                    sent.update { it + 1 }
                }
                is DmSendResult.Failed -> setState(clientId, DmDeliveryState.Failed)
            }
        } catch (cancellation: CancellationException) {
            // Leaving the thread cancels the send; the bubble is dropped with the
            // coordinator. Don't mark it failed (it may well have been delivered).
            throw cancellation
        } catch (_: Exception) {
            setState(clientId, DmDeliveryState.Failed)
        }
    }

    private fun setState(clientId: String, state: DmDeliveryState) {
        pending.update { list ->
            list.map { if (it.clientId == clientId) it.copy(deliveryState = state) else it }
        }
    }

    /**
     * Reconciles the optimistic bubbles against the live server messages: a
     * pending bubble whose id (clientId) now appears as a delivered document id
     * in [live] is dropped, since the real doc supersedes it. Idempotent; called
     * by the route on every live-thread update.
     */
    fun onLiveMessages(live: List<DmMessage>) {
        if (pending.value.isEmpty()) return
        val liveIds = live.mapTo(HashSet(live.size)) { it.id }
        pending.update { list -> list.filter { it.id !in liveIds } }
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
}
