package com.kungsbackacarcommunity.app.feedback

import java.util.UUID

/**
 * "Open tickets" browser — pure (Android/Firebase-free) domain model for the
 * in-app list of OPEN GitHub issues a member can browse, +1 or comment on.
 *
 * Mirrors the member-readable `openTickets/{issueNumber}` Firestore document
 * (a backend-maintained mirror of open public GitHub issues) and the
 * `feedback-interactWithIssue` callable contract. Kept pure so the list mapping
 * and the (optimistic) interaction-state machine are JVM-unit-testable without
 * emulators or Compose.
 */

/** One row of the openTickets mirror as the app renders it. */
data class OpenTicket(
    /** GitHub issue number; also the Firestore doc id and the interact key. */
    val number: Int,
    val title: String,
    val summary: String,
    /** github.com issue URL — validated again at open time via [isGitHubWebUrl]. */
    val htmlUrl: String,
    val plusOneCount: Int,
    val commentCount: Int,
)

/** The two things a member may do ONCE each per issue (backend-enforced dedup). */
enum class TicketInteractionType {
    PLUS_ONE,
    COMMENT,
}

/**
 * Outcome of a `feedback-interactWithIssue` call. The callable's `posted` response
 * distinguishes successful GitHub delivery from a committed interaction whose
 * comment could not be published. HttpsErrors are mapped from stable codes and
 * details, never their text.
 */
enum class TicketInteractOutcome {
    POSTED,
    DELIVERY_FAILED,
    ALREADY_DONE,
    RATE_LIMITED,
    FAILED,
}

/** Maps the callable's delivery result; a reserved interaction cannot be retried. */
internal fun ticketOutcomeFromResponse(response: Any?): TicketInteractOutcome =
    when ((response as? Map<*, *>)?.get("posted") as? Boolean) {
        true -> TicketInteractOutcome.POSTED
        false -> TicketInteractOutcome.DELIVERY_FAILED
        null -> TicketInteractOutcome.FAILED
    }

/** Why an interaction control shows an inline error (drives which string). */
enum class TicketInteractionError {
    ALREADY_DONE,
    RATE_LIMITED,
    EMPTY_COMMENT,
    UNKNOWN,
}

/**
 * Per-ticket UX state for the two controls. Because the backend `issueInteractions`
 * dedup ledger is NOT client-readable, this is an OPTIMISTIC, session-local view:
 * a control is marked done after a successful (or already-done) call and stays
 * disabled for the rest of the session. A duplicate that slips through across a
 * cold start is caught by the backend and reported back as [TicketInteractOutcome.ALREADY_DONE],
 * which also flips the flag. If the backend reserved an interaction but could not
 * publish it to GitHub, that control is also disabled and the delivery failure is
 * shown instead of offering a retry.
 */
data class TicketInteractionState(
    val plusOneDone: Boolean = false,
    val commentDone: Boolean = false,
    val plusOneDeliveryFailed: Boolean = false,
    val commentDeliveryFailed: Boolean = false,
    /** The control currently mid-flight, or null when idle. */
    val submitting: TicketInteractionType? = null,
    val error: TicketInteractionError? = null,
) {
    /** A +1 may be offered only when it is not already done and nothing is in flight. */
    val canPlusOne: Boolean get() = !plusOneDone && !plusOneDeliveryFailed && submitting == null

    /** A comment may be submitted only when not already done and nothing is in flight. */
    val canComment: Boolean get() = !commentDone && !commentDeliveryFailed && submitting == null

    val isPlusOneSubmitting: Boolean get() = submitting == TicketInteractionType.PLUS_ONE
    val isCommentSubmitting: Boolean get() = submitting == TicketInteractionType.COMMENT
}

/** The observable state of the ticket LIST (distinct from per-row interaction state). */
sealed interface OpenTicketsListState {
    data object Loading : OpenTicketsListState

    data class Loaded(val tickets: List<OpenTicket>) : OpenTicketsListState

    /** The listener failed before any snapshot arrived (never after a good load). */
    data object Error : OpenTicketsListState
}

/**
 * Comment bounds + validation, mirroring the backend `MAX_TICKET_COMMENT_LENGTH`
 * (openTickets-core.ts). The backend re-bounds after mention-neutralisation, so
 * this client cap is advisory (a UX nicety), never the security boundary.
 */
object TicketComments {
    const val MAX_COMMENT_LENGTH = 1000

    /** Trimmed + capped comment text ready to send. */
    fun bound(text: String): String = text.trim().take(MAX_COMMENT_LENGTH)

    /** True when [text] has non-whitespace content to post. */
    fun isValid(text: String): Boolean = text.trim().isNotEmpty()
}

/**
 * A fresh idempotency/debug tag matching the callable's `clientId` schema
 * `[A-Za-z0-9_-]{1,64}`. A dashless UUID hex (32 chars, [0-9a-f]) satisfies it;
 * it is recorded on the interaction doc but is NOT part of the dedup identity.
 */
fun randomTicketClientId(): String = UUID.randomUUID().toString().replace("-", "")
