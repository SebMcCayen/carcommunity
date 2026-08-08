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
        /**
         * Each friend's PUBLIC Crown Points balance (uid → balance), overlaid
         * onto the list after it loads. A uid ABSENT here has no read yet, no
         * wallet, or a read that failed — the screen renders that as 0. Empty
         * until the best-effort points read completes (or in a build with no
         * points repository wired), so the list always renders first and the
         * numbers fill in without ever blocking or failing it.
         */
        val points: Map<String, Long> = emptyMap(),
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
    private val pointsRepository: FriendPointsRepository? = null,
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

    // Keys of rows whose accept/decline/cancel/remove callable is currently in
    // flight. Guards against overlapping invocations from rapid taps and lets the
    // UI disable that row's action buttons while it runs.
    //
    // Every key is NAMESPACED by action ([respondBusyKey] / [cancelBusyKey] /
    // [removeBusyKey]): the ids come from three different spaces — a requestId
    // (accept/decline), a recipient uid (cancel) and a friend uid (remove) — and
    // as bare strings in one Set they could be equal and mark the wrong row busy
    // (e.g. a friend uid colliding with the recipient uid of a pending request to
    // the same person). The prefix makes a cross-type collision impossible. The
    // screen builds its lookup keys with the SAME helpers, so the two never drift.
    private val inFlightRows = MutableStateFlow<Set<String>>(emptySet())
    val busyRows: StateFlow<Set<String>> = inFlightRows.asStateFlow()

    suspend fun load() {
        try {
            when (val result = repository.list()) {
                is FriendsResult.Loaded -> {
                    val loaded =
                        FriendsStatus.Loaded(
                            friends = result.data.friends,
                            incoming = result.data.incoming,
                            outgoing = result.data.outgoing,
                        )
                    // Publish the list FIRST so it renders immediately, then
                    // overlay each friend's Crown Points as a best-effort second
                    // step that can never fail or delay the list.
                    statusState.value = loaded
                    overlayPoints(loaded)
                }
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

    /**
     * Fills in the friends' public Crown Points balances after the list has been
     * published. Deliberately SILENT on failure: the balances are a decorative
     * overlay ("how active they have been"), never load-bearing, so a failed or
     * empty read simply leaves the list without numbers rather than surfacing an
     * error or filing a fault. No-ops when no points repository is wired or the
     * list is empty.
     *
     * The overlay is applied only if [loaded] is still the current status — a
     * mutation (accept/remove/…) that reloaded the snapshot in the meantime wins,
     * so stale points can never clobber a newer list.
     */
    private suspend fun overlayPoints(loaded: FriendsStatus.Loaded) {
        val repo = pointsRepository ?: return
        if (loaded.friends.isEmpty()) return
        val balances =
            try {
                repo.balancesFor(loaded.friends.map { it.uid })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                emptyMap<String, Long>()
            }
        if (balances.isEmpty()) return
        statusState.update { current ->
            if (current === loaded) loaded.copy(points = balances) else current
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
        val key = respondBusyKey(requestId)
        // Ignore a second tap on a row whose accept/decline is already running.
        if (key in inFlightRows.value) return
        inFlightRows.update { it + key }
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
            inFlightRows.update { it - key }
        }
    }

    /**
     * Withdraws the caller's OWN pending outgoing request to [toUid] (the "Cancel
     * request" affordance on an outgoing row — a request sent by mistake). Keyed
     * in [inFlightRows] by the recipient uid, matching how the outgoing row is
     * identified; a second tap while the callable is running is dropped.
     *
     * The callable is idempotent: whether it deletes a live request or answers a
     * silent no-op, the post-state is "I no longer have a pending request to this
     * member", so success always resyncs the snapshot — the row disappears. A
     * mapped [CancelResult.Failed] (the callable answered with an error code)
     * also resyncs, so a request already handled server-side does not linger. A
     * thrown exception — the callable never returned a mapped result (network
     * drop, App Check failure) — only surfaces the generic error without a
     * reload, matching [respond]/[remove]: the call did not complete, so the
     * outgoing row is left untouched and the next [load] reconciles it.
     */
    suspend fun cancel(toUid: String) {
        val key = cancelBusyKey(toUid)
        if (key in inFlightRows.value) return
        inFlightRows.update { it + key }
        rowError.value = null
        try {
            when (val result = repository.cancelRequest(toUid)) {
                CancelResult.Cancelled -> load()
                is CancelResult.Failed -> {
                    rowError.value = result.error
                    reportIfFault("cancelRequest", result.error, result.diagnostic)
                    // The request may be gone/handled server-side — resync so the
                    // stale outgoing row disappears rather than lingering.
                    load()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            rowError.value = FriendActionError.Generic
            reportIfFault("cancelRequest", FriendActionError.Generic, error::class.java.simpleName)
        } finally {
            inFlightRows.update { it - key }
        }
    }

    suspend fun remove(friendUid: String) {
        val key = removeBusyKey(friendUid)
        // Ignore a second tap on a friend whose removal is already running.
        if (key in inFlightRows.value) return
        inFlightRows.update { it + key }
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
            inFlightRows.update { it - key }
        }
    }

    /** Clears the add-friend sub-state (e.g. after dismissing a picker/result). */
    fun resetAdd() {
        addState.value = AddFriendState.Idle
    }

    fun clearActionError() {
        rowError.value = null
    }

    /**
     * [busyRows] key namespacing. Accept/decline are keyed by requestId, cancel
     * by the recipient uid, remove by the friend uid — three DIFFERENT id spaces
     * that must not collide in the single [busyRows] Set. The screen builds its
     * lookup keys with these same helpers so a row is marked busy iff its own
     * action is in flight. `internal` so [FriendsScreen] can share them.
     */
    companion object {
        internal fun respondBusyKey(requestId: String): String = "respond:$requestId"

        internal fun cancelBusyKey(toUid: String): String = "cancel:$toUid"

        internal fun removeBusyKey(friendUid: String): String = "remove:$friendUid"
    }
}
