package com.kungsbackacarcommunity.app.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Orchestrates the (optimistic) per-ticket interaction state for the open-tickets
 * browser. Pure Kotlin (Firebase-free) so the state machine is unit-testable with
 * a fake [OpenTicketsRepository].
 *
 * ALREADY-INTERACTED APPROACH (option a): the backend `issueInteractions` dedup
 * ledger is NOT client-readable, so a control is disabled OPTIMISTICALLY after a
 * successful call and stays disabled for the session. A duplicate that survives a
 * cold start is rejected by the backend as `failed-precondition`, mapped here to
 * [TicketInteractOutcome.ALREADY_DONE], which ALSO flips the flag — so the disable
 * is eventually consistent without any owner-readable signal.
 */
class OpenTicketsCoordinator(
    private val repository: OpenTicketsRepository,
    private val clientId: () -> String = ::randomTicketClientId,
) {
    private val state = MutableStateFlow<Map<Int, TicketInteractionState>>(emptyMap())

    /** Per-issue interaction state; issues absent from the map are at defaults. */
    val interactions: StateFlow<Map<Int, TicketInteractionState>> = state.asStateFlow()

    /** State for one issue, defaulting when it has never been touched. */
    fun stateFor(issueNumber: Int): TicketInteractionState =
        state.value[issueNumber] ?: TicketInteractionState()

    /** Registers a "me too" +1 on [issueNumber], once per session. */
    suspend fun plusOne(issueNumber: Int) {
        val current = stateFor(issueNumber)
        if (!current.canPlusOne) return
        update(issueNumber) { it.copy(submitting = TicketInteractionType.PLUS_ONE, error = null) }
        runInteraction(issueNumber, TicketInteractionType.PLUS_ONE, null) { done ->
            copy(plusOneDone = plusOneDone || done)
        }
    }

    /** Posts a member comment on [issueNumber], once per session. Empty text is rejected locally. */
    suspend fun comment(issueNumber: Int, text: String) {
        val current = stateFor(issueNumber)
        if (!current.canComment) return
        val bounded = TicketComments.bound(text)
        if (bounded.isEmpty()) {
            update(issueNumber) { it.copy(error = TicketInteractionError.EMPTY_COMMENT) }
            return
        }
        update(issueNumber) { it.copy(submitting = TicketInteractionType.COMMENT, error = null) }
        runInteraction(issueNumber, TicketInteractionType.COMMENT, bounded) { done ->
            copy(commentDone = commentDone || done)
        }
    }

    /** Clears an inline error for one issue (e.g. when the user edits the comment field). */
    fun clearError(issueNumber: Int) {
        val current = state.value[issueNumber] ?: return
        if (current.error == null) return
        update(issueNumber) { it.copy(error = null) }
    }

    /**
     * Shared tail for both interaction types: calls the repository, then maps the
     * outcome onto the row. [markDone] applies the type-specific "done" flag when
     * the outcome means the control should be permanently disabled.
     */
    private suspend fun runInteraction(
        issueNumber: Int,
        type: TicketInteractionType,
        text: String?,
        markDone: TicketInteractionState.(done: Boolean) -> TicketInteractionState,
    ) {
        val outcome =
            try {
                repository.interact(issueNumber, type, text, clientId())
            } catch (cancellation: CancellationException) {
                // Never swallow cancellation; leave the row mid-flight cleared so a
                // retry is possible after the scope restarts.
                update(issueNumber) { it.copy(submitting = null) }
                throw cancellation
            } catch (failure: Exception) {
                TicketInteractOutcome.FAILED
            }
        update(issueNumber) { row ->
            when (outcome) {
                // POSTED and ALREADY_DONE both disable the control; ALREADY_DONE
                // additionally shows a brief "already marked" note.
                TicketInteractOutcome.POSTED ->
                    row.markDone(true).copy(submitting = null, error = null)

                TicketInteractOutcome.ALREADY_DONE ->
                    row.markDone(true)
                        .copy(submitting = null, error = TicketInteractionError.ALREADY_DONE)

                TicketInteractOutcome.RATE_LIMITED ->
                    row.copy(submitting = null, error = TicketInteractionError.RATE_LIMITED)

                TicketInteractOutcome.FAILED ->
                    row.copy(submitting = null, error = TicketInteractionError.UNKNOWN)
            }
        }
    }

    private fun update(issueNumber: Int, transform: (TicketInteractionState) -> TicketInteractionState) {
        // Atomic read-modify-write: +1/comment for different tickets run in
        // separate coroutines, so a plain `state.value = state.value…` could lose
        // a concurrent write. MutableStateFlow.update retries its lambda on a CAS
        // miss, so both updates land.
        state.update { current ->
            current.toMutableMap().apply {
                this[issueNumber] = transform(this[issueNumber] ?: TicketInteractionState())
            }
        }
    }
}
