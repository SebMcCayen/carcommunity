package com.kungsbackacarcommunity.app.friends

/**
 * Friends access. Every operation is a member-gated europe-west1 callable;
 * there is no client Firestore listener (the graph is read via `friend-list`
 * and re-fetched after each mutation). Firebase-free interface for testability.
 */
interface FriendsRepository {
    /** Fetches the caller's friends + incoming/outgoing pending requests. */
    suspend fun list(): FriendsResult

    /** Sends a request to whoever owns [nickname] (may be ambiguous). */
    suspend fun sendRequestByNickname(nickname: String): SendRequestResult

    /** Sends a request to a specific [toUid] (used to resolve an ambiguity). */
    suspend fun sendRequestToUid(toUid: String): SendRequestResult

    /** Accepts or declines an incoming request. */
    suspend fun respond(requestId: String, accept: Boolean): RespondResult

    /**
     * Withdraws the caller's own pending outgoing request to [toUid].
     *
     * Addressed by RECIPIENT rather than by request id: the backend derives the
     * request document from (caller, toUid), so a caller can only ever cancel a
     * request they themselves sent — and the client needs no id to do it.
     * Idempotent; an already-handled (or never sent) request is a no-op.
     */
    suspend fun cancelRequest(toUid: String): CancelResult

    /** Removes an established friend. Idempotent. */
    suspend fun remove(friendUid: String): RemoveResult
}
