package com.kungsbackacarcommunity.app.chatchannels

import com.kungsbackacarcommunity.app.chat.ChatReportReason
import com.kungsbackacarcommunity.app.diagnostics.CrashFeatures
import com.kungsbackacarcommunity.app.diagnostics.CrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.NoopCrashTelemetry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * UI-facing status of a message report on a channel, mirroring
 * [com.kungsbackacarcommunity.app.chat.ChatReportStatus] on event chat. Report
 * runs on its own flow, entirely separate from [ChannelPageStatus] and the send
 * pipeline, so filing a report never blocks (or is blocked by) sending or paging.
 */
sealed interface ChannelReportStatus {
    data object Idle : ChannelReportStatus

    data object Reporting : ChannelReportStatus

    /** The report reached the backend. */
    data object Done : ChannelReportStatus

    /** The report failed; the reason is deliberately not distinguished to the reporter. */
    data object Failed : ChannelReportStatus
}

/**
 * Orchestrates one open channel (community OR convoy): OPTIMISTIC send, older-page
 * pagination (accumulated off the live newest-window), and an optional mark-read
 * (both channels carry a last-read marker; convoy's is per convoy, so its
 * [marker] closes over the convoy id). Pure Kotlin (no Firebase/Android types) so it is
 * unit-testable with fakes; the live message stream itself is collected in the
 * route. Mirrors dm/DmThreadCoordinator, generalized over the two channel kinds
 * via injected [sender]/[pager]/[marker] lambdas so community and convoy share
 * one coordinator instead of duplicating this state machine.
 *
 * Send is optimistic: [send] appends a local "sending" bubble to
 * [pendingMessages] IMMEDIATELY (so the message shows the instant the user taps,
 * with no wait for the `*-post` round-trip) and fires the callable in the
 * background. On success the bubble flips to [ChannelDeliveryState.Sent]; on
 * failure to [ChannelDeliveryState.Failed] with a [retry] affordance (the user's
 * message is never silently dropped). The route displays
 * [ChannelThread.mergeWithPending] of the server messages + these bubbles, and the
 * bubble is reconciled away — rendered exactly once — the moment the real document
 * arrives from the listener ([onLiveMessages]), matched by its clientId (which the
 * backend stores as the delivered doc id).
 *
 * Idempotency: each optimistic bubble carries a generated clientId sent to the
 * backend as the message doc id, so a [retry] resends the SAME key and the backend
 * writes exactly one message however many times it is retried.
 *
 * [sentCount] increments on every successful send; unused for re-subscription
 * (channel docs already exist), but kept for parity + tests.
 */
class ChannelChatCoordinator(
    // (text, mentionedUids, clientId, replyToMessageId) -> result. The route wires
    // this to the channel's `*-post`; replyToMessageId is null for an ordinary
    // (non-reply) message.
    private val sender: suspend (String, List<String>, String, String?) -> ChannelSendResult,
    private val pager: suspend (String) -> ChannelOlderResult,
    private val selfUid: String,
    private val marker: (suspend () -> Unit)? = null,
    // (messageId, reason) -> result. Wired to the channel's `chatchannels-reportMessage`
    // by the route on a surface whose report is live (community today); null on a
    // surface with no report backend (convoy), where the report row is never shown
    // and [report] is a no-op.
    private val reporter: (suspend (String, ChatReportReason) -> ChannelReportResult)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /**
     * Crash telemetry for the UNEXPECTED-throw branch of [send]. Defaults to the
     * no-op so unit tests need no Firebase.
     */
    private val crashTelemetry: CrashTelemetry = NoopCrashTelemetry,
) {
    private val pending = MutableStateFlow<List<ChannelMessage>>(emptyList())

    /**
     * The caller's in-flight/failed optimistic bubbles, oldest-first. Each is a
     * [ChannelMessage] whose id == its clientId and whose
     * [ChannelMessage.deliveryState] is [ChannelDeliveryState.Sending],
     * [ChannelDeliveryState.Sent] (acked, awaiting the listener), or
     * [ChannelDeliveryState.Failed]. Merged into the displayed thread by
     * [ChannelThread.mergeWithPending] and pruned by [onLiveMessages] once
     * delivered.
     */
    val pendingMessages: StateFlow<List<ChannelMessage>> = pending.asStateFlow()

    private val older = MutableStateFlow<List<ChannelMessage>>(emptyList())
    val olderMessages: StateFlow<List<ChannelMessage>> = older.asStateFlow()

    private val page = MutableStateFlow<ChannelPageStatus>(ChannelPageStatus.Idle)
    val pageStatus: StateFlow<ChannelPageStatus> = page.asStateFlow()

    private val sent = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = sent.asStateFlow()

    private val dropped = MutableStateFlow(0)

    /**
     * How many @mentions the server DROPPED across the sends the user has not
     * acknowledged yet — the composer's optimistic picks reconciled against the
     * accepted set each `*-post` response echoed back. Nonzero means a member the
     * user deliberately named was not notified (they deleted their account, lost
     * their subscription, or blocked the sender between picking and posting),
     * which is worth one quiet line; the message itself was still posted.
     * Self-mentions and duplicates cannot land here — the picker excludes the
     * caller and the draft dedupes — so every drop counted is a real one.
     *
     * ACCUMULATES rather than tracking only the latest send, and is cleared ONLY
     * by [dismissDroppedMentions]. Optimistic send frees the composer the instant
     * a message is queued, so several sends are routinely in flight at once and
     * their acks can resolve in any order. Resetting this per send — or letting
     * each ack overwrite it — would let a later send that dropped nothing wipe an
     * earlier send's note before the user ever saw it, silently swallowing the one
     * signal that a mention didn't reach anyone. The count only ever falls when
     * the user dismisses it.
     */
    val droppedMentionCount: StateFlow<Int> = dropped.asStateFlow()

    private val report = MutableStateFlow<ChannelReportStatus>(ChannelReportStatus.Idle)

    /**
     * Status of the caller's most recent message report. Its own flow so a report
     * never blocks — and is never blocked by — sending or paging (mirrors
     * [com.kungsbackacarcommunity.app.chat.ChatCoordinator.reportStatus]).
     */
    val reportStatus: StateFlow<ChannelReportStatus> = report.asStateFlow()

    /**
     * Reports the message [messageId] with [reason]. A no-op when no [reporter] is
     * wired (a surface with no report backend) or a report is already in flight, so
     * a double-tap can't file twice. On success the status flips to
     * [ChannelReportStatus.Done], on failure to [ChannelReportStatus.Failed]; either
     * is cleared by [resetReport]. The reporter never surfaces WHY it failed — the
     * backend deliberately doesn't reveal whether a prior report already existed.
     */
    suspend fun report(messageId: String, reason: ChatReportReason) {
        val submit = reporter ?: return
        if (report.value == ChannelReportStatus.Reporting) return
        report.value = ChannelReportStatus.Reporting
        try {
            report.value =
                when (submit(messageId, reason)) {
                    ChannelReportResult.Reported -> ChannelReportStatus.Done
                    ChannelReportResult.Failed -> ChannelReportStatus.Failed
                }
        } catch (cancellation: CancellationException) {
            // Leaving the channel cancels the report; drop back to Idle rather than
            // stranding the banner (the report may well have landed).
            report.value = ChannelReportStatus.Idle
            throw cancellation
        } catch (_: Exception) {
            report.value = ChannelReportStatus.Failed
        }
    }

    /** Clears the report status back to Idle (after showing Done/Failed). */
    fun resetReport() {
        report.value = ChannelReportStatus.Idle
    }

    /**
     * Optimistically sends [text] (optionally @mentioning [mentionedUids], and
     * optionally as an inline reply to [replyTo]). Adds the "sending" bubble
     * synchronously (the first thing this does, so the UI updates before the
     * suspending callable), then dispatches the `*-post` callable with a fresh
     * idempotency key. When [replyTo] is present the bubble carries the
     * client-built quote snapshot (so its quote header shows at once) and the
     * parent's [ChannelReplyTo.messageId] is sent as `replyToMessageId`; the server
     * rebuilds the authoritative snapshot on the delivered document.
     */
    suspend fun send(
        text: String,
        mentionedUids: List<String> = emptyList(),
        replyTo: ChannelReplyTo? = null,
    ) {
        if (!ChannelThread.isSendable(text)) return
        val trimmed = text.trim()
        val clientId = idGenerator()
        val picked = mentionedUids.distinct()
        val optimistic =
            ChannelMessage(
                id = clientId,
                senderUid = selfUid,
                text = trimmed,
                // Own bubbles render no sender header (isOwn), so no self profile
                // is needed for display.
                senderDisplayName = null,
                senderAvatarPath = null,
                createdAtMillis = clock(),
                createdAtIso = null,
                // Optimistically highlight the picked mentions; the accepted set
                // reconciles them on the delivered doc via the listener.
                mentionedUids = picked,
                clientId = clientId,
                deliveryState = ChannelDeliveryState.Sending,
                replyTo = replyTo,
            )
        pending.update { it + optimistic }
        dispatch(clientId, trimmed, picked, replyTo?.messageId)
    }

    /**
     * Re-attempts a previously [ChannelDeliveryState.Failed] bubble, resending the
     * SAME clientId so the backend stays exactly-once (no double post). A no-op if
     * the bubble isn't found or isn't in a failed state, so a double-tap can't fire
     * two resends.
     */
    suspend fun retry(clientId: String) {
        val target =
            pending.value.firstOrNull {
                it.clientId == clientId && it.deliveryState == ChannelDeliveryState.Failed
            } ?: return
        pending.update { list ->
            list.map {
                if (it.clientId == clientId) {
                    it.copy(deliveryState = ChannelDeliveryState.Sending, sendError = null)
                } else {
                    it
                }
            }
        }
        // Resend carries the SAME reply target the failed bubble held, so a retried
        // reply stays a reply.
        dispatch(clientId, target.text, target.mentionedUids, target.replyTo?.messageId)
    }

    private suspend fun dispatch(
        clientId: String,
        text: String,
        mentionedUids: List<String>,
        replyToMessageId: String?,
    ) {
        try {
            when (val result = sender(text, mentionedUids, clientId, replyToMessageId)) {
                is ChannelSendResult.Sent -> {
                    // Flip to Sent so the "sending" affordance clears on the ack;
                    // the bubble is removed for good once the listener delivers the
                    // real doc ([onLiveMessages]), matched by clientId == doc id.
                    markSent(clientId)
                    // Atomic: concurrent optimistic sends each resolve on their own
                    // coroutine, so a plain read-modify-write could drop increments.
                    sent.update { it + 1 }
                    // Reconcile: the accepted set is authoritative, and may be a
                    // strict subset of what we sent.
                    val accepted = result.mentionedUids.toSet()
                    val droppedHere = mentionedUids.count { it !in accepted }
                    // Atomic ADD, never an assignment: concurrent sends resolve on
                    // their own coroutines and in any order, so assigning would let
                    // whichever ack happens to land last decide the note — including
                    // a mention-free send zeroing a drop the user hasn't seen.
                    if (droppedHere > 0) dropped.update { it + droppedHere }
                }
                // Keep the SPECIFIC error so the UI can explain why and offer a
                // retry only when it could actually help ([ChannelSendError.isRetryable]).
                is ChannelSendResult.Failed -> markFailed(clientId, result.error)
            }
        } catch (cancellation: CancellationException) {
            // Leaving the channel cancels the send; the bubble is dropped with the
            // coordinator. Don't mark it failed (it may well have been delivered).
            throw cancellation
        } catch (failure: Exception) {
            // An unexpected throw is transient/unknown — a retryable Generic.
            // Same reasoning as DmThreadCoordinator.send: every KNOWN failure
            // arrives as a mapped ChannelSendResult.Failed, so reaching here is
            // an unmodelled path. Record the stack trace as a non-fatal; only
            // the stable feature path leaves the device, never the message body,
            // channel id, or any uid.
            crashTelemetry.recordNonFatal(CrashFeatures.CHANNEL_SEND, failure)
            markFailed(clientId, ChannelSendError.Generic)
        }
    }

    private fun markSent(clientId: String) {
        pending.update { list ->
            list.map {
                if (it.clientId == clientId) {
                    it.copy(deliveryState = ChannelDeliveryState.Sent, sendError = null)
                } else {
                    it
                }
            }
        }
    }

    private fun markFailed(clientId: String, error: ChannelSendError) {
        pending.update { list ->
            list.map {
                if (it.clientId == clientId) {
                    it.copy(deliveryState = ChannelDeliveryState.Failed, sendError = error)
                } else {
                    it
                }
            }
        }
    }

    /**
     * Reconciles the optimistic bubbles against the live server messages: a
     * pending bubble whose id (clientId) now appears as a delivered document id in
     * [live] is dropped, since the real doc supersedes it. Idempotent; called by
     * the route on every live update.
     */
    fun onLiveMessages(live: List<ChannelMessage>) {
        if (pending.value.isEmpty()) return
        val liveIds = live.mapTo(HashSet(live.size)) { it.id }
        pending.update { list -> list.filter { it.id !in liveIds } }
    }

    /** Dismisses the dropped-mention note (e.g. once the user edits a new draft). */
    fun dismissDroppedMentions() {
        dropped.value = 0
    }

    /** Marks the channel read (no-op when [marker] is null). Best-effort. */
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
}
