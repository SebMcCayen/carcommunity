package com.kungsbackacarcommunity.app.convoy

/**
 * Convoy access. Every operation is a member-gated europe-west1 callable; there
 * is no client Firestore listener in the management UI (the convoy set is read
 * via `convoy-list` and re-fetched after each mutation). Firebase-free interface
 * for testability.
 */
interface ConvoyRepository {
    /** Fetches the caller's convoys + their pending invites. */
    suspend fun list(): ConvoyListResult

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
