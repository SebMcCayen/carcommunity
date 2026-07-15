package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** UI-facing status of the convoy snapshot (my convoys + pending invites). */
sealed interface ConvoyListStatus {
    data object Loading : ConvoyListStatus

    data class Loaded(
        /** Every convoy the caller belongs to (owner or accepted/invited member). */
        val convoys: List<ConvoySummary>,
        /** The subset the caller has a still-open invite to (Accept/Decline). */
        val pendingInvites: List<ConvoySummary>,
    ) : ConvoyListStatus {
        private val pendingIds: Set<String> = pendingInvites.mapTo(HashSet()) { it.convoyId }

        /**
         * The "my convoys" list with the still-pending invites removed, so a
         * convoy the caller hasn't answered yet appears ONLY in the invites
         * section (never duplicated in both). Ordering from the backend
         * (createdAt desc) is preserved.
         */
        val myConvoys: List<ConvoySummary> = convoys.filterNot { it.convoyId in pendingIds }

        /** Looks up a single convoy by id for the detail/summary view. */
        fun convoy(convoyId: String): ConvoySummary? =
            convoys.firstOrNull { it.convoyId == convoyId }
    }

    data class Error(val error: ConvoyActionError) : ConvoyListStatus
}

/** Sub-state of the "create convoy" flow. */
sealed interface CreateConvoyState {
    data object Idle : CreateConvoyState

    data object Working : CreateConvoyState

    /**
     * The convoy was created. [convoyId] lets the screen navigate straight into
     * the new detail; [skipped] surfaces any invitees that couldn't be added.
     */
    data class Created(
        val convoyId: String,
        val skipped: List<SkippedInvitee>,
    ) : CreateConvoyState

    data class Error(val error: ConvoyActionError) : CreateConvoyState
}

/**
 * Orchestrates the convoy management surface (load + create + respond + start +
 * end). Pure Kotlin so it is unit-testable with a fake repository. There is no
 * live listener — every successful mutation re-fetches the snapshot via [load].
 * The detail/summary views read a single convoy out of the loaded snapshot by
 * id, so a start/end re-fetch updates them without extra plumbing.
 */
class ConvoyCoordinator(
    private val repository: ConvoyRepository,
) {
    private val statusState = MutableStateFlow<ConvoyListStatus>(ConvoyListStatus.Loading)
    val status: StateFlow<ConvoyListStatus> = statusState.asStateFlow()

    private val createStateFlow = MutableStateFlow<CreateConvoyState>(CreateConvoyState.Idle)
    val createState: StateFlow<CreateConvoyState> = createStateFlow.asStateFlow()

    // Failures of a row/detail action (accept/decline/start/end) — surfaced once,
    // then cleared. Success is reflected by the reloaded snapshot, not a status.
    private val rowError = MutableStateFlow<ConvoyActionError?>(null)
    val actionError: StateFlow<ConvoyActionError?> = rowError.asStateFlow()

    // Keys (convoyId) whose accept/decline/start/end callable is currently in
    // flight. Guards against overlapping invocations from rapid taps and lets the
    // UI disable that convoy's action buttons while it runs.
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())
    val busyConvoys: StateFlow<Set<String>> = inFlight.asStateFlow()

    suspend fun load() {
        try {
            when (val result = repository.list()) {
                is ConvoyListResult.Loaded ->
                    statusState.value =
                        ConvoyListStatus.Loaded(
                            convoys = result.convoys,
                            pendingInvites = result.pendingInvites,
                        )
                is ConvoyListResult.Failed -> statusState.value = ConvoyListStatus.Error(result.error)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            statusState.value = ConvoyListStatus.Error(ConvoyActionError.Generic)
        }
    }

    suspend fun create(inviteeUids: List<String>, title: String?) {
        if (createStateFlow.value == CreateConvoyState.Working) return
        val invitees = inviteeUids.filter { it.isNotBlank() }.distinct()
        if (invitees.isEmpty()) {
            createStateFlow.value = CreateConvoyState.Error(ConvoyActionError.NoInvitees)
            return
        }
        createStateFlow.value = CreateConvoyState.Working
        try {
            createStateFlow.value =
                when (val result = repository.create(invitees, title?.trim()?.takeIf { it.isNotEmpty() })) {
                    is CreateConvoyResult.Created ->
                        CreateConvoyState.Created(result.convoy.convoyId, result.skipped)
                    is CreateConvoyResult.Failed -> CreateConvoyState.Error(result.error)
                }
            // A created convoy changes the snapshot — refresh so it shows up.
            if (createStateFlow.value is CreateConvoyState.Created) load()
        } catch (cancellation: CancellationException) {
            createStateFlow.value = CreateConvoyState.Idle
            throw cancellation
        } catch (_: Exception) {
            createStateFlow.value = CreateConvoyState.Error(ConvoyActionError.Generic)
        }
    }

    suspend fun accept(convoyId: String) = respond(convoyId, accept = true)

    suspend fun decline(convoyId: String) = respond(convoyId, accept = false)

    private suspend fun respond(convoyId: String, accept: Boolean) {
        runRowAction(convoyId) { repository.respond(convoyId, accept).errorOrNull() }
    }

    suspend fun start(convoyId: String) {
        runRowAction(convoyId) { repository.start(convoyId).errorOrNull() }
    }

    suspend fun end(convoyId: String) {
        runRowAction(convoyId) { repository.end(convoyId).errorOrNull() }
    }

    /**
     * Runs a single-convoy mutation guarded against rapid double-taps, then
     * always re-fetches the snapshot (so a success updates the list/detail and a
     * stale row disappears). [action] returns the mapped error on failure, or
     * null on success.
     */
    private suspend fun runRowAction(convoyId: String, action: suspend () -> ConvoyActionError?) {
        if (convoyId in inFlight.value) return
        inFlight.update { it + convoyId }
        rowError.value = null
        try {
            val error = action()
            if (error != null) rowError.value = error
            // Resync regardless of outcome: on success the list reflects the new
            // state; on failure a stale/handled row is reconciled.
            load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            rowError.value = ConvoyActionError.Generic
        } finally {
            inFlight.update { it - convoyId }
        }
    }

    /** Clears the create sub-state (e.g. after dismissing the result). */
    fun resetCreate() {
        createStateFlow.value = CreateConvoyState.Idle
    }

    fun clearActionError() {
        rowError.value = null
    }
}

/** Maps a mutation result to its error (or null on success), for [runRowAction]. */
private fun ConvoyMutationResult.errorOrNull(): ConvoyActionError? =
    when (this) {
        is ConvoyMutationResult.Updated -> null
        is ConvoyMutationResult.Failed -> error
    }
