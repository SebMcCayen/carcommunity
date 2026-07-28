package com.kungsbackacarcommunity.app.profile

/**
 * A member's CURRENT public identity, as read from `users/{uid}`.
 *
 * Deliberately the same two fields every denormalized copy in the app carries,
 * so an overlay is a straight field-for-field replacement rather than a merge
 * with per-field rules.
 */
data class LiveProfile(
    val displayName: String?,
    val avatarPath: String?,
)

/**
 * Client half of live-profile hydration: replacing the DENORMALIZED
 * `displayName`/`avatarPath` copies carried by chat messages, DM conversations
 * and convoy rosters with the member's current profile at render time.
 *
 * ## Why these surfaces need it at all
 *
 * Those copies are written ONCE, at the moment the underlying document is
 * written, and nothing rewrites them afterwards:
 *  - `conversations/{pairId}.memberProfiles` — `dm.sendMessage` refreshes only
 *    the SENDER's own entry on each send, so the OTHER party's inbox card stays
 *    frozen until they next message you.
 *  - `communityChat/global/messages/{id}` and `convoyChats/{id}/messages/{id}` —
 *    the sender's profile is stamped onto every message, so old messages keep an
 *    old avatar forever.
 *  - `convoys/{id}.memberProfiles` — captured at create/invite and never
 *    refreshed (`convoy.respond` writes invite status only).
 *
 * Meanwhile the member-profile screen reads live `users/{uid}`. So a member who
 * changes — or first uploads — an avatar shows the new one on their profile and
 * the old one (or none) everywhere above. That asymmetry is the reported bug.
 *
 * ## Why the fix is CLIENT-side, unlike the friends list
 *
 * The friends list is served by the `friend-list` callable, so it is fixed
 * server-side by re-reading the live profiles before answering
 * (`functions/src/friends/manageFriends.ts loadLiveProfiles`). These three
 * surfaces are NOT: the DM inbox, the chat live windows and the convoy detail
 * document are all direct Firestore snapshot listeners on the client, and the
 * chat/convoy callables serve only the paged tail. Hydrating the callables would
 * therefore fix a page nobody renders first, leave the live window stale, and —
 * for convoy, where the listener merges over the callable result — be overwritten
 * by the next snapshot. So the overlay has to sit where BOTH paths land, which is
 * here. `users/{uid}` is readable by any authenticated user
 * (firebase/firestore.rules), so no rules change is involved.
 *
 * This mirrors [com.kungsbackacarcommunity.app.blocking.BlockVisibility], the
 * other cross-cutting client-side overlay, for the same structural reason: the
 * live windows are listeners that no server-side pass can reach.
 *
 * Pure Kotlin, no Firebase, so every rule below is JVM-unit-testable.
 */
object LiveProfiles {

    /**
     * The member's identity to render, given the stored denormalized copy and
     * the live profiles loaded for this batch.
     *
     * Two rules, and the difference between them is the whole point of keying
     * [live] by presence rather than storing a flat avatar path:
     *
     *  - A live profile that EXISTS wins WHOLESALE, including its nulls. A member
     *    who DELETES their avatar must stop showing it everywhere; treating the
     *    live null as "no opinion" and falling back would resurrect the deleted
     *    picture, which is worse than the bug being fixed.
     *  - A live profile that is ABSENT — a deleted account, or a read that failed
     *    — falls back to the stored copy. Absent means "nothing live to say about
     *    this member", NOT "this member has no avatar", and conflating the two
     *    would blank rows on a transient read failure.
     */
    fun resolve(uid: String, stored: LiveProfile, live: Map<String, LiveProfile>): LiveProfile =
        live[uid] ?: stored

    /**
     * The DISTINCT member uids named by [items], for a single batched profile
     * read.
     *
     * De-duplication is the load-bearing part on the chat surfaces: a channel
     * window of hundreds of messages is typically a handful of distinct senders,
     * and one read per MESSAGE would be an unacceptable cost for a screen that
     * re-renders on every new message. Blank uids are dropped — a malformed
     * document must not turn into a wasted document read.
     */
    fun <T> uidsOf(items: List<T>, uidOf: (T) -> String?): Set<String> {
        if (items.isEmpty()) return emptySet()
        return items.mapNotNullTo(mutableSetOf()) { item -> uidOf(item)?.takeIf { it.isNotBlank() } }
    }
}
