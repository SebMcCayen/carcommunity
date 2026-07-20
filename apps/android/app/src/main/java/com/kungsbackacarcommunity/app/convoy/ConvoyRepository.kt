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

    /** Owner-only: moves a forming convoy to active. */
    suspend fun start(convoyId: String): ConvoyMutationResult

    /** Owner-only: ends the convoy (computes + stores the summary). */
    suspend fun end(convoyId: String): ConvoyMutationResult
}
