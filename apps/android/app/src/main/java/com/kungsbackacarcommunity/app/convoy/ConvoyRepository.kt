package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.flow.Flow

/**
 * Convoy access. Mutations and the initial list are member-gated europe-west1
 * callables; the caller's convoy SET is read via `convoy-list` and re-fetched
 * after each mutation. On top of that polled read, a SINGLE active convoy can be
 * watched LIVE via [observeConvoy] — a Firestore snapshot listener on its
 * document — so a shared destination, a member join/leave, or a status change set
 * by someone else propagates without waiting for the next refresh. Firebase-free
 * interface for testability.
 */
interface ConvoyRepository {
    /** Fetches the caller's convoys + their pending invites. */
    suspend fun list(): ConvoyListResult

    /**
     * Emits the LIVE state of a single convoy document as a Firestore snapshot
     * listener fires (destination / membership / status / summary), for
     * [callerUid] (used to derive the viewer + accepted-live set, exactly as the
     * `convoy-list` callable does server-side). Emits null when the document is
     * missing or the read is denied — e.g. after the caller leaves and drops out
     * of `memberUids` — so a caller can distinguish "gone" from "unchanged".
     *
     * The listener is bound to the returned [Flow]: it attaches on collection and
     * detaches when collection stops (see [FirebaseConvoyRepository.observeConvoy]),
     * so scoping the listener to "while in this convoy" is a matter of scoping the
     * collection. It is deliberately NOT part of the management list read — it is
     * one document, watched only while the caller is actively in that convoy.
     */
    fun observeConvoy(convoyId: String, callerUid: String?): Flow<ConvoySummary?>

    /**
     * Creates a convoy inviting [inviteeUids] (must be the owner's friends;
     * non-friends/blocked are skipped). [title] is optional.
     */
    suspend fun create(inviteeUids: List<String>, title: String?): CreateConvoyResult

    /** Accepts or declines the caller's pending invite to [convoyId]. */
    suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult

    /**
     * Adds [inviteeUids] to an EXISTING convoy (any accepted member may invite;
     * each candidate must be the CALLER's friend — non-friends/blocked/already-in
     * are skipped). Returns the same `{ convoy, invited, skipped }` shape as
     * [create], so the result reuses [CreateConvoyResult].
     */
    suspend fun invite(convoyId: String, inviteeUids: List<String>): CreateConvoyResult

    /**
     * Removes the CALLER from a convoy they have ACCEPTED — available to ANY
     * accepted member, the LEADER included (leadership transfers to another
     * member). Returns the refreshed convoy, whose `viewer` is now null because
     * the caller is no longer a member, plus what the exit did to the convoy:
     * whether it survived or ENDED because too few would have been left, and who
     * inherited leadership. See [LeaveConvoyResult] / [ConvoyLeaveOutcome].
     */
    suspend fun leave(convoyId: String): LeaveConvoyResult

    /** Owner-only: moves a forming convoy to active. */
    suspend fun start(convoyId: String): ConvoyMutationResult

    /**
     * LEADER-ONLY: ends the convoy for EVERYONE (computes + stores the summary).
     * A member who is not the leader is refused server-side; the UI only offers
     * this to the leader ([ConvoyBar.exitChoice]).
     */
    suspend fun end(convoyId: String): ConvoyMutationResult
}
