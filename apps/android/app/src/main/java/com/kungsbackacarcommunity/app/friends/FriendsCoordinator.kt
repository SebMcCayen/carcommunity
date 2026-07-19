package com.kungsbackacarcommunity.app.friends

import com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing status of the friends snapshot (list + pending requests). */
sealed interface FriendsStatus {
    data object Loading : FriendsStatus

    data class Loaded(
        val friends: List<FriendSummary>,
        val incoming: List<FriendRequestSummary>,
        val outgoing: List<FriendRequestSummary>,
    ) : FriendsStatus

    /**
     * The snapshot failed to load. Carries the mapped [error] so the screen can
     * surface the specific auth/member-gating message (via its `friends.*`
     * mapping) rather than a single generic load-error string.
     */
    data class Error(val error: FriendActionError) : FriendsStatus
}

/** Sub-state of the "add friend by nickname" flow. */
sealed interface AddFriendState {
    data object Idle : AddFriendState

    data object Working : AddFriendState

    /** The nickname was ambiguous — the screen shows a member picker. */
    data class Chooser(val candidates: List<FriendUser>) : AddFriendState

    data class Error(val error: FriendActionError) : AddFriendState

    /** The request landed. [nowFriends] is true when it auto-accepted an inbound one. */
    data class Sent(val nowFriends: Boolean) : AddFriendState
}

/**
 * Orchestrates the friends screen (load + add + respond + remove). Pure Kotlin
 * so it is unit-testable with a fake repository. There is no live listener, so
 * every successful mutation re-fetches the snapshot via [load].
 *
 * ERROR REPORTING: exactly two categories are reported through [errorReporter]
 * (the shared `errors-reportClientError` pipeline, which dedupes and files a
 * GitHub issue) — see [reportIfFault], which is the enforcing code:
 *  - [FriendActionError.Generic]: the "we could not classify this" case, the
 *    one a user can only describe as "Something went wrong".
 *  - [FriendActionError.TemporarilyUnavailable]: classified, but still our
 *    fault — the backend answered that it cannot serve the request.
 * The other categories are normal, actionable outcomes of what the user typed
 * (unknown nickname, already friends, request already sent, ...) or of
 * connectivity ([FriendActionError.Network]); filing an issue for those would
 * bury the real faults in noise, so they are deliberately NOT reported.
 *
 * The reported `message` is a fixed, app-generated string plus the unmapped
 * status code — never the nickname the user typed, and never any other user
 * content (the pipeline's no-PII rule).
 */
class FriendsCoordinator(
    private val repository: FriendsRepository,
    private val errorReporter: ClientErrorReporter? = null,
) {
    /**
     * Reports a genuine fault; normal, actionable outcomes are skipped.
     *
     * Two categories qualify. [FriendActionError.Generic] is the unclassified
     * one — the failure a user can only describe as "something went wrong".
     * [FriendActionError.TemporarilyUnavailable] is classified but is still OUR
     * fault (the backend answered "I cannot serve this"), and it is exactly the
     * class of failure that went unnoticed in production for days because it
     * was rendered as an ordinary load error and never reported.
     *
     * Everything else — an unknown nickname, an already-sent request, being
     * signed out, a dropped connection — is a normal outcome, and filing issues
     * for those would bury the real faults in noise.
     */
    private fun reportIfFault(
        operation: String,
        error: FriendActionError,
        diagnostic: FriendErrorDiagnostic,
    ) {
        val message = when (error) {
            FriendActionError.Generic -> "friend-$operation failed with an unmapped error"
            FriendActionError.TemporarilyUnavailable ->
                "friend-$operation failed: backend reported it cannot serve the request"
            else -> return
        }
        errorReporter?.report(
            feature = "friends.$operation",
            message = message,
            code = diagnostic ?: "UNKNOWN",
        )
    }

    private val statusState = MutableStateFlow<FriendsStatus>(FriendsStatus.Loading)
    val status: StateFlow<FriendsStatus> = statusState.asStateFlow()

    private val addState = MutableStateFlow<AddFriendState>(AddFriendState.Idle)
    val add: StateFlow<AddFriendState> = addState.asStateFlow()

    // Failures of a row action (accept/decline/remove) — surfaced once, then
    // cleared. Success is reflected by the reloaded snapshot, not a status.
    private val rowError = MutableStateFlow<FriendActionError?>(null)
    val actionError: StateFlow<FriendActionError?> = rowError.asStateFlow()

    // Keys (requestId / friendUid) of rows whose accept/decline/remove callable
    // is currently in flight. Guards against overlapping invocations from rapid
    // taps and lets the UI disable that row's action buttons while it runs.
    private val inFlightRows = MutableStateFlow<Set<String>>(emptySet())
    val busyRows: StateFlow<Set<String>> = inFlightRows.asStateFlow()

    suspend fun load() {
        try {
            when (val result = repository.list()) {
                is FriendsResult.Loaded ->
                    statusState.value =
                        FriendsStatus.Loaded(
                            friends = result.data.friends,
                            incoming = result.data.incoming,
                            outgoing = result.data.outgoing,
                        )
                is FriendsResult.Failed -> {
                    statusState.value = FriendsStatus.Error(result.error)
                    reportIfFault("list", result.error, result.diagnostic)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            statusState.value = FriendsStatus.Error(FriendActionError.Generic)
            reportIfFault("list", FriendActionError.Generic, error::class.java.simpleName)
        }
    }

    suspend fun sendRequestByNickname(nickname: String) {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty()) {
            addState.value = AddFriendState.Error(FriendActionError.Invalid)
            return
        }
        runSend { repository.sendRequestByNickname(trimmed) }
    }

    /** Resolves an ambiguous nickname by re-sending to the chosen candidate. */
    suspend fun chooseCandidate(uid: String) = runSend { repository.sendRequestToUid(uid) }

    private suspend fun runSend(action: suspend () -> SendRequestResult) {
        if (addState.value == AddFriendState.Working) return
        addState.value = AddFriendState.Working
        try {
            addState.value =
                when (val result = action()) {
                    SendRequestResult.Requested -> AddFriendState.Sent(nowFriends = false)
                    SendRequestResult.NowFriends -> AddFriendState.Sent(nowFriends = true)
                    is SendRequestResult.Ambiguous -> AddFriendState.Chooser(result.candidates)
                    is SendRequestResult.Failed -> {
                        reportIfFault("sendRequest", result.error, result.diagnostic)
                        AddFriendState.Error(result.error)
                    }
                }
            // A landed request/friendship changes the pending lists — refresh, so
            // the new outgoing "waiting for a reply" row appears immediately.
            if (addState.value is AddFriendState.Sent) load()
        } catch (cancellation: CancellationException) {
            addState.value = AddFriendState.Idle
            throw cancellation
        } catch (error: Exception) {
            addState.value = AddFriendState.Error(FriendActionError.Generic)
            reportIfFault("sendRequest", FriendActionError.Generic, error::class.java.simpleName)
        }
    }

    suspend fun accept(requestId: String) = respond(requestId, accept = true)

    suspend fun decline(requestId: String) = respond(requestId, accept = false)

    private suspend fun respond(requestId: String, accept: Boolean) {
        // Ignore a second tap on a row whose accept/decline is already running.
        if (requestId in inFlightRows.value) return
        inFlightRows.update { it + requestId }
        rowError.value = null
        try {
            when (val result = repository.respond(requestId, accept)) {
                RespondResult.Accepted, RespondResult.Declined -> load()
                is RespondResult.Failed -> {
                    rowError.value = result.error
                    reportIfFault("respondRequest", result.error, result.diagnostic)
                    // The request may be gone/handled server-side — resync so the
                    // stale row disappears rather than lingering.
                    load()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            rowError.value = FriendActionError.Generic
            reportIfFault("respondRequest", FriendActionError.Generic, error::class.java.simpleName)
        } finally {
            inFlightRows.update { it - requestId }
        }
    }

    suspend fun remove(friendUid: String) {
        // Ignore a second tap on a friend whose removal is already running.
        if (friendUid in inFlightRows.value) return
        inFlightRows.update { it + friendUid }
        rowError.value = null
        try {
            when (val result = repository.remove(friendUid)) {
                RemoveResult.Removed -> load()
                is RemoveResult.Failed -> {
                    rowError.value = result.error
                    reportIfFault("remove", result.error, result.diagnostic)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            rowError.value = FriendActionError.Generic
            reportIfFault("remove", FriendActionError.Generic, error::class.java.simpleName)
        } finally {
            inFlightRows.update { it - friendUid }
        }
    }

    /** Clears the add-friend sub-state (e.g. after dismissing a picker/result). */
    fun resetAdd() {
        addState.value = AddFriendState.Idle
    }

    fun clearActionError() {
        rowError.value = null
    }
}
