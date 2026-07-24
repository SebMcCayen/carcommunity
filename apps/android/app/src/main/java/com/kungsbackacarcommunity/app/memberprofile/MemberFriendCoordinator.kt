package com.kungsbackacarcommunity.app.memberprofile

import com.kungsbackacarcommunity.app.friends.CancelResult
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendRelationship
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import com.kungsbackacarcommunity.app.friends.RespondResult
import com.kungsbackacarcommunity.app.friends.SendRequestResult
import com.kungsbackacarcommunity.app.friends.resolveFriendRelationship
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which friend callable is currently in flight, if any. */
enum class FriendActionInFlight { Send, Cancel, Accept, Decline }

/** The friend affordance a member profile should render. */
sealed interface MemberFriendControl {
    /**
     * Render nothing at all: the relationship isn't known yet (or couldn't be
     * loaded). Deliberately NOT "Add friend" — offering it before we know would
     * invite a request to someone the viewer is already friends with, which the
     * backend can only answer with an avoidable error.
     */
    data object None : MemberFriendControl

    data object Add : MemberFriendControl

    /** A request the viewer sent is still pending — offer to withdraw it. */
    data object CancelRequest : MemberFriendControl

    /** This member asked the VIEWER — offer accept + decline. */
    data object Respond : MemberFriendControl

    /**
     * Already friends. A status, not an action: unfriending stays on the
     * Friends screen (which owns the confirm dialog), so a profile visit can't
     * end a friendship by one stray tap.
     */
    data object Friends : MemberFriendControl
}

/**
 * Friend-action state for one member profile.
 *
 * @param relationship the viewer's relationship to the profile owner.
 * @param inFlight the callable currently running; every control is disabled
 *   while this is non-null, which is what makes a double-tap a no-op.
 * @param error the last failure, cleared when the next action starts.
 */
data class MemberFriendState(
    val relationship: FriendRelationship = FriendRelationship.Unknown,
    val inFlight: FriendActionInFlight? = null,
    val error: FriendActionError? = null,
) {
    /** The control to render for [relationship] — pure, and unit-tested as such. */
    val control: MemberFriendControl
        get() = when (relationship) {
            FriendRelationship.Unknown -> MemberFriendControl.None
            FriendRelationship.None -> MemberFriendControl.Add
            FriendRelationship.OutgoingPending -> MemberFriendControl.CancelRequest
            is FriendRelationship.IncomingPending -> MemberFriendControl.Respond
            FriendRelationship.Friends -> MemberFriendControl.Friends
        }

    /** False while a callable is running, so the controls disable themselves. */
    val enabled: Boolean get() = inFlight == null
}

/**
 * Drives the friend action on ANOTHER member's profile: resolves the current
 * relationship, then sends / withdraws / answers a request. Pure Kotlin (no
 * Firebase, no Compose) so the whole state machine is unit-testable with a fake
 * repository.
 *
 * WHERE THE RELATIONSHIP COMES FROM: the viewer's own `friend-list` snapshot,
 * never a read of the target's data. `users/{uid}/friends` is owner-only under
 * firebase/firestore.rules, so the viewer's own side of the graph is the only
 * lawful source — and it already carries everything needed (friends plus both
 * pending directions). Hence no new read path and no rules change.
 *
 * OPTIMISM: a successful mutation moves [MemberFriendState.relationship] to its
 * known post-state IMMEDIATELY (send → outgoing pending, cancel → none, accept
 * → friends, decline → none) so the control flips without waiting for a
 * re-fetch, and only then re-syncs from the backend. A FAILURE leaves the
 * relationship untouched and surfaces the mapped error, so the control the user
 * tapped is still the one in front of them.
 *
 * An action counts as in flight until its re-sync finishes, so overlapping taps
 * cannot interleave their reads (see [run]).
 *
 * A re-sync that itself fails deliberately KEEPS the optimistic value rather
 * than dropping back to [FriendRelationship.Unknown] (which would hide the
 * control entirely): the mutation already succeeded, so its post-state is the
 * best knowledge available, and re-entering the screen re-reads anyway.
 *
 * Error REPORTING is deliberately absent here: the friends error pipeline lives
 * in [com.kungsbackacarcommunity.app.friends.FriendsCoordinator], and reporting
 * the same categories from two places would file each fault twice.
 */
class MemberFriendCoordinator(
    private val repository: FriendsRepository,
    private val targetUid: String,
) {
    private val _state = MutableStateFlow(MemberFriendState())
    val state: StateFlow<MemberFriendState> = _state.asStateFlow()

    /**
     * Reads the viewer's friend graph and resolves the relationship to the
     * profile owner.
     *
     * A failed load leaves the relationship [FriendRelationship.Unknown] — the
     * control simply stays hidden — and raises NO error banner: the friend
     * action is secondary content on someone else's profile, and a red notice
     * about a graph the viewer never asked to see would read as though the
     * profile itself had failed. Failures of what the user DID tap are surfaced.
     */
    suspend fun load() {
        try {
            when (val result = repository.list()) {
                is FriendsResult.Loaded ->
                    _state.value = _state.value.copy(
                        relationship = resolveFriendRelationship(result.data, targetUid),
                    )
                is FriendsResult.Failed -> Unit
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Same reasoning as a Failed result: stay Unknown, stay quiet.
        }
    }

    /** Sends a friend request to the profile owner. */
    suspend fun sendRequest() = run(FriendActionInFlight.Send) {
        when (val result = repository.sendRequestToUid(targetUid)) {
            SendRequestResult.Requested -> Outcome.Settled(FriendRelationship.OutgoingPending)
            SendRequestResult.NowFriends -> Outcome.Settled(FriendRelationship.Friends)
            // Ambiguity belongs to the NICKNAME path: this call names a resolved
            // uid, so the backend cannot answer with candidates. An unexpected
            // one is a fault, not a silent no-op.
            is SendRequestResult.Ambiguous -> Outcome.Failed(FriendActionError.Generic)
            is SendRequestResult.Failed -> Outcome.Failed(result.error)
        }
    }

    /** Withdraws the viewer's own pending request to the profile owner. */
    suspend fun cancelRequest() = run(FriendActionInFlight.Cancel) {
        when (val result = repository.cancelRequest(targetUid)) {
            CancelResult.Cancelled -> Outcome.Settled(FriendRelationship.None)
            is CancelResult.Failed -> Outcome.Failed(result.error)
        }
    }

    /** Accepts the profile owner's pending request to the viewer. */
    suspend fun acceptRequest() = respond(accept = true)

    /** Declines the profile owner's pending request to the viewer. */
    suspend fun declineRequest() = respond(accept = false)

    private suspend fun respond(accept: Boolean) {
        // The request id is only known while the relationship IS an incoming
        // request; anything else means the state moved under the tap, and there
        // is nothing left to answer.
        val requestId =
            (_state.value.relationship as? FriendRelationship.IncomingPending)?.requestId ?: return
        run(if (accept) FriendActionInFlight.Accept else FriendActionInFlight.Decline) {
            when (val result = repository.respond(requestId, accept)) {
                RespondResult.Accepted -> Outcome.Settled(FriendRelationship.Friends)
                RespondResult.Declined -> Outcome.Settled(FriendRelationship.None)
                is RespondResult.Failed -> Outcome.Failed(result.error)
            }
        }
    }

    /**
     * Runs one action end to end: guards duplicate taps, clears the previous
     * error, applies the returned post-state optimistically, and re-syncs.
     *
     * The guard is the single reason a double-tap cannot send two requests —
     * the second invocation returns before touching the repository.
     *
     * It is held until the RE-SYNC finishes, not just until the mutation
     * returns. Each tap runs in its own coroutine, so releasing it earlier
     * would let a second action start while the first one's `load()` is still
     * in flight; that older, slower read could then land LAST and overwrite the
     * newer state — a quick send-then-cancel would snap back to "request
     * pending" and make a cancel that really happened look undone.
     */
    private suspend fun run(action: FriendActionInFlight, mutate: suspend () -> Outcome) {
        if (_state.value.inFlight != null) return
        _state.value = _state.value.copy(inFlight = action, error = null)
        try {
            when (val outcome = mutate()) {
                is Outcome.Settled -> {
                    _state.value = _state.value.copy(relationship = outcome.relationship)
                    // Re-sync only once a mutation actually landed: after a
                    // failure the backend state is unchanged, so a re-fetch
                    // would spend a round trip confirming what is on screen.
                    load()
                }
                is Outcome.Failed -> _state.value = _state.value.copy(error = outcome.error)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            _state.value = _state.value.copy(error = FriendActionError.Generic)
        } finally {
            // Runs on the cancellation path too, so an abandoned action can
            // never leave the controls permanently disabled.
            _state.value = _state.value.copy(inFlight = null)
        }
    }

    private sealed interface Outcome {
        data class Settled(val relationship: FriendRelationship) : Outcome

        data class Failed(val error: FriendActionError) : Outcome
    }
}
