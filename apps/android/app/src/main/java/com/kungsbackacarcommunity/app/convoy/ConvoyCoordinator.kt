package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * Sub-state of the "invite more people into an existing convoy" flow, driven from
 * the convoy bar's invite picker. Mirrors [CreateConvoyState] but never navigates
 * anywhere — inviting grows the convoy the caller is already in.
 */
sealed interface InviteConvoyState {
    data object Idle : InviteConvoyState

    data object Working : InviteConvoyState

    /**
     * The invites were sent. [invited] is the list of invitees the backend
     * actually added (so the confirmation can say how many were invited);
     * [skipped] surfaces any requested invitee that couldn't be added (not a
     * friend / already in / blocked), with the same neutral reasons as create.
     * The confirmation snackbar is built from both counts so it reflects
     * reality — all invited, all skipped, or a mix.
     */
    data class Done(
        val invited: List<String>,
        val skipped: List<SkippedInvitee>,
    ) : InviteConvoyState

    data class Error(val error: ConvoyActionError) : InviteConvoyState
}

/**
 * Orchestrates the convoy management surface (load + create + respond + invite +
 * leave + start + end). Pure Kotlin so it is unit-testable with a fake repository.
 * Every
 * successful mutation re-fetches the snapshot via [load]; the detail/summary
 * views read a single convoy out of the loaded snapshot by id, so a start/end
 * re-fetch updates them without extra plumbing.
 *
 * On top of that polled read, [observeActiveConvoy] optionally watches the ONE
 * active convoy LIVE (a Firestore snapshot listener via
 * [ConvoyRepository.observeConvoy]) and folds each update back into [status], so
 * a shared destination or a membership/status change made by another member
 * appears without waiting for a re-fetch. The driving surface (the convoy bar)
 * starts it; the management list does not need it.
 */
class ConvoyCoordinator(
    private val repository: ConvoyRepository,
    /**
     * The SHARED-destination half of the convoy domain, which has no deployed
     * callables yet — hence the default: [UnavailableConvoyDestinationRepository]
     * refuses every call without touching the network, and the bar's destination
     * controls render disabled ([ConvoyDestinations.availability]). When
     * `convoy-setDestination` / `convoy-clearDestination` ship, this default is
     * replaced by [FirebaseConvoyDestinationRepository] at the construction site
     * and the flag is flipped. Nothing below changes.
     */
    private val destinationRepository: ConvoyDestinationRepository =
        UnavailableConvoyDestinationRepository,
) {
    private val statusState = MutableStateFlow<ConvoyListStatus>(ConvoyListStatus.Loading)
    val status: StateFlow<ConvoyListStatus> = statusState.asStateFlow()

    private val createStateFlow = MutableStateFlow<CreateConvoyState>(CreateConvoyState.Idle)
    val createState: StateFlow<CreateConvoyState> = createStateFlow.asStateFlow()

    private val inviteStateFlow = MutableStateFlow<InviteConvoyState>(InviteConvoyState.Idle)
    val inviteState: StateFlow<InviteConvoyState> = inviteStateFlow.asStateFlow()

    // Failures of a row/detail action (accept/decline/start/end) — surfaced once,
    // then cleared. Success is reflected by the reloaded snapshot, not a status.
    private val rowError = MutableStateFlow<ConvoyActionError?>(null)
    val actionError: StateFlow<ConvoyActionError?> = rowError.asStateFlow()

    // Keys (convoyId) whose accept/decline/start/end callable is currently in
    // flight. Guards against overlapping invocations from rapid taps and lets the
    // UI disable that convoy's action buttons while it runs.
    private val inFlight = MutableStateFlow<Set<String>>(emptySet())
    val busyConvoys: StateFlow<Set<String>> = inFlight.asStateFlow()

    /**
     * Watches the ACTIVE convoy (the one the bar describes — see
     * [ConvoyBar.activeConvoy]) LIVE, folding each Firestore snapshot back into
     * [status] so a shared destination, a member join/leave, or a status change
     * set by someone ELSE reaches the bar/map without waiting for a re-fetch. This
     * is the piece #486's instant shared destination was waiting on.
     *
     * Lifecycle (attach/detach), by construction rather than by bookkeeping:
     *  - It derives the active convoy id from [status] and watches ONLY that one
     *    document — never the whole convoy set.
     *  - [collectLatest] means when the active convoy changes (a switch, or it
     *    ends / the caller leaves and it drops out of the active set → id becomes
     *    null), the previous [ConvoyRepository.observeConvoy] collection is
     *    cancelled, which runs its `awaitClose` and removes the Firestore listener.
     *    A null id attaches nothing.
     *  - This function suspends for as long as it observes, so the caller scopes
     *    the listener's whole lifetime by scoping the coroutine (a screen-scoped
     *    `LaunchedEffect`): leaving the screen cancels it and detaches the
     *    listener. A leaked listener — the thing that would bill and drain battery
     *    — is therefore not reachable.
     *
     * Offline persistence / double-emit: an unchanged snapshot maps to a
     * [ConvoySummary] equal to the one already in [status], and [mergeConvoyUpdate]
     * then produces a [ConvoyListStatus] equal to the current one, which the
     * [StateFlow] drops — so the cache-then-server emissions Firestore delivers do
     * not churn the UI. A null emission (doc gone / read denied) is ignored so the
     * last good value is kept until a real change arrives.
     *
     * Must be started AFTER (or concurrently with) [load]: the merge only applies
     * to a convoy already present in the loaded snapshot, so a live update for a
     * convoy the list has not yet produced is a no-op until [load] lands it.
     */
    suspend fun observeActiveConvoy(viewerUid: String?) {
        statusState
            .map { ConvoyBar.activeConvoy(it)?.convoyId }
            .distinctUntilChanged()
            .collectLatest { convoyId ->
                if (convoyId == null) return@collectLatest
                repository.observeConvoy(convoyId, viewerUid).collect { fresh ->
                    if (fresh != null) {
                        statusState.update { mergeConvoyUpdate(it, fresh) }
                    }
                }
            }
    }

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
        // ITEM 1 client guard: the caller is already an active participant of a
        // convoy, so a new one is refused. Fail locally with the correct reason
        // instead of calling the backend, whose overloaded failed-precondition the
        // code-only mapper would render as the misleading "no one could be added".
        // The backend transaction remains the authoritative gate.
        if (ConvoyBar.activeConvoy(statusState.value) != null) {
            createStateFlow.value = CreateConvoyState.Error(ConvoyActionError.AlreadyInConvoy)
            return
        }
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

    suspend fun accept(convoyId: String) {
        // ITEM 1 client guard: accepting JOINS a second convoy, refused while the
        // caller is already an active participant of another (ConvoyBar.activeConvoy
        // only ever returns a convoy the caller has ACCEPTED, never this pending
        // invite). Declining stays available. Backend re-checks authoritatively.
        if (ConvoyBar.activeConvoy(statusState.value) != null) {
            rowError.value = ConvoyActionError.AlreadyInConvoy
            return
        }
        respond(convoyId, accept = true)
    }

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
     * Removes the caller from [convoyId] (a non-owner member's action — the owner
     * ends instead). Runs through [runRowAction], so it is guarded against double
     * taps, surfaces a failure via [actionError], and re-fetches on completion: on
     * success the caller drops out of the convoy and the bar hides itself (the
     * refreshed snapshot no longer has an accepted membership for them).
     */
    suspend fun leave(convoyId: String) {
        runRowAction(convoyId) { repository.leave(convoyId).errorOrNull() }
    }

    /**
     * Invites [inviteeUids] into an existing [convoyId]. Mirrors [create] (working
     * → done/error, then a re-fetch on success so the new invitees show), but the
     * caller stays in the convoy they already have — there is nothing to navigate
     * into. The result's [InviteConvoyState.Done.skipped] carries any invitee the
     * backend couldn't add.
     */
    suspend fun invite(convoyId: String, inviteeUids: List<String>) {
        if (inviteStateFlow.value == InviteConvoyState.Working) return
        val invitees = inviteeUids.filter { it.isNotBlank() }.distinct()
        if (invitees.isEmpty()) {
            inviteStateFlow.value = InviteConvoyState.Error(ConvoyActionError.NoInvitees)
            return
        }
        inviteStateFlow.value = InviteConvoyState.Working
        try {
            inviteStateFlow.value =
                when (val result = repository.invite(convoyId, invitees)) {
                    is CreateConvoyResult.Created -> InviteConvoyState.Done(result.invited, result.skipped)
                    is CreateConvoyResult.Failed -> InviteConvoyState.Error(result.error)
                }
            // New members change the snapshot — refresh so the count/roster update.
            if (inviteStateFlow.value is InviteConvoyState.Done) load()
        } catch (cancellation: CancellationException) {
            inviteStateFlow.value = InviteConvoyState.Idle
            throw cancellation
        } catch (_: Exception) {
            inviteStateFlow.value = InviteConvoyState.Error(ConvoyActionError.Generic)
        }
    }

    /**
     * Clears the invite sub-state (e.g. after dismissing the picker/result) back
     * to [InviteConvoyState.Idle].
     *
     * Deliberately a no-op while an invite is [InviteConvoyState.Working]: the
     * `convoy-invite` call is in flight and [invite] relies on the `Working`
     * state as its overlap guard (it early-returns while `Working`). Clearing it
     * here — as happens when the picker is opened or dismissed mid-flight — would
     * drop the guard and let a second concurrent invite start on top of the first.
     * The in-flight coroutine owns the transition out of `Working` (→ Done/Error),
     * so callers dismissing the UI can safely leave the sub-state intact.
     */
    fun resetInvite() {
        if (inviteStateFlow.value == InviteConvoyState.Working) return
        inviteStateFlow.value = InviteConvoyState.Idle
    }

    /**
     * Sets (or replaces) the convoy's shared destination, then re-fetches like
     * every other mutation so the new destination reaches the bar through the
     * one convoy read path.
     */
    suspend fun setDestination(
        convoyId: String,
        latitude: Double,
        longitude: Double,
        label: String?,
    ) {
        runRowAction(convoyId) {
            destinationRepository
                .setDestination(convoyId, latitude, longitude, label)
                .errorOrNull()
        }
    }

    /** Clears the convoy's shared destination (setter or owner only). */
    suspend fun clearDestination(convoyId: String) {
        runRowAction(convoyId) { destinationRepository.clearDestination(convoyId).errorOrNull() }
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
            // Re-fetch here too so the doc's "always re-fetches" holds and the
            // snapshot stays consistent after an unexpected failure. load() has
            // its own guard and never rethrows, so it can't mask this rowError.
            load()
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

/**
 * Same, for the destination results.
 *
 * [ConvoyDestinationResult.Unavailable] maps to NO error on purpose. It means
 * "this callable was never built", which is not a failure the user caused and not
 * something to show them a red line about — the control that would have produced
 * it is disabled and already carries an honest explanation. Turning it into
 * [ConvoyActionError.Generic] would put "something went wrong" on a feature that
 * simply does not exist yet.
 */
private fun ConvoyDestinationResult.errorOrNull(): ConvoyActionError? =
    when (this) {
        is ConvoyDestinationResult.Updated -> null
        is ConvoyDestinationResult.Failed -> error
        ConvoyDestinationResult.Unavailable -> null
    }
