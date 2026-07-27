package com.kungsbackacarcommunity.app.blocking

/**
 * Client half of block invisibility: dropping a mutually-hidden author from a
 * live message window.
 *
 * ## Why the client filters at all
 *
 * Blocking is enforced on the server wherever a read goes through a callable
 * (`communityChat-list`, `convoyChat-list`, `dm-listConversations`,
 * `dm-getMessages`, `live-listNearby`) and at the rules layer wherever a rule
 * can express it (the RTDB live-marker read, and the DM messages subcollection —
 * every message there shares one pair, so the condition is constant across the
 * query).
 *
 * The channel LIVE windows are neither. They are direct Firestore snapshot
 * listeners on `communityChat/global/messages`, `convoyChats/{id}/messages` and
 * `events/{id}/messages`, and a Firestore security rule CANNOT filter a list
 * query: a condition that varies per document (here, per sender) fails the whole
 * query instead of dropping the offending row. Moving those windows behind a
 * callable would cost the realtime behaviour they exist for.
 *
 * So for those three surfaces the filter runs here, against the server-maintained
 * `blockVisibility/{uid}.hiddenUids` mirror. That is an honest client-side
 * filter, with the honest limitation: the hidden message document is still
 * DELIVERED to the device and a modified client could render it. It is not a
 * confidentiality boundary for a shared channel — those channels are readable by
 * every active member by design — it is the mutual-invisibility behaviour the
 * product asks for. The DM surface, where confidentiality does matter, is the
 * one that is genuinely rules-enforced.
 *
 * Pure Kotlin, no Firebase, so the filter is JVM-unit-testable.
 */
object BlockVisibility {

    /**
     * Drops every item authored by a uid in [hidden].
     *
     * [hidden] is the symmetric union maintained by the backend — uids this user
     * blocked AND uids that blocked this user — so a single containment check
     * covers both directions. An item whose author is null is KEPT: a malformed
     * document is a rendering problem, not a block-evasion route (none of these
     * collections accept client writes).
     */
    fun <T> filterHiddenAuthors(
        items: List<T>,
        hidden: Set<String>,
        authorUidOf: (T) -> String?,
    ): List<T> {
        if (hidden.isEmpty()) return items
        return items.filter { authorUidOf(it) !in hidden }
    }

    /**
     * The newest item authored by a visible uid, or null when every candidate is
     * hidden (or the window is empty).
     *
     * Used for the channel unread dot, which is derived from the newest message
     * alone: if that message is from a blocked party the dot must not light for
     * something the user will never be shown.
     */
    fun <T> newestVisible(items: List<T>, hidden: Set<String>, authorUidOf: (T) -> String?): T? =
        filterHiddenAuthors(items, hidden, authorUidOf).firstOrNull()
}
