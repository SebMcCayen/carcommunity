package com.kungsbackacarcommunity.app.usersearch

/**
 * Member typeahead access. One member-gated europe-west1 callable
 * (`userSearch-members`); there is deliberately no client Firestore query (see
 * [UserSearch.kt]). Firebase-free interface so the coordinator is testable with
 * a fake.
 */
interface UserSearchRepository {
    /**
     * Searches members whose nickname STARTS WITH [query], case-insensitively.
     *
     * [query] is the raw text the user typed; the backend normalizes it. The
     * call is expected to be made from a cancellable coroutine — cancelling it
     * is how the caller discards a stale in-flight search.
     */
    suspend fun search(query: String): UserSearchOutcome
}
