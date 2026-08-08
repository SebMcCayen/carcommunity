package com.kungsbackacarcommunity.app.dm

import com.kungsbackacarcommunity.app.friends.FriendShareTargets
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsData

/**
 * Which thread the "start a new dialogue" picker opens for a chosen friend, and
 * whether that thread already exists in the caller's inbox.
 *
 * [isExisting] is informational only (it does not change how the thread is
 * opened — a self-derived [dmPairId] resolves the same document either way, and
 * ChatRoute's first send creates a not-yet-existing one). It exists so the host
 * and tests can distinguish "re-open a conversation you already have" from
 * "start a brand-new one".
 */
data class DmOpenTarget(
    val uid: String,
    val displayName: String?,
    val isExisting: Boolean,
)

/**
 * Pure logic behind the DM inbox's "start a new dialogue" friend picker. Kept
 * free of Compose/Firebase so the eligibility filtering and the
 * selection → open-target mapping are unit-testable on a plain JVM, and so the
 * picker can never disagree with the other "pick a friend" surfaces on who is
 * eligible.
 */
object NewDialogue {
    /**
     * The friends a NEW DM may be started with: the established friends only
     * (a pending request is not yet a friend and cannot receive a DM), blank-uid
     * rows dropped, name-ordered for a scannable picker. Delegates to the shared
     * [FriendShareTargets] so every "pick a friend" entry point agrees.
     */
    fun targets(data: FriendsData): List<FriendSummary> = FriendShareTargets.from(data)

    /**
     * Resolves which thread the picked [friend] opens, given the caller's current
     * inbox [conversations].
     *
     * When a conversation with that friend already exists, its (live-hydrated)
     * inbox display name is preferred over the friend-row name — the two are
     * usually equal, but the inbox card is the name the member just saw — and
     * [DmOpenTarget.isExisting] is true. Otherwise a brand-new thread is opened
     * with the friend-row name.
     *
     * A blank/absent name on the existing row never shadows a usable friend-row
     * name (falls through to it), so the opened thread is titled as well as it
     * can be from whichever source has a name.
     */
    fun openTargetFor(
        friend: FriendSummary,
        conversations: List<DmConversation>,
    ): DmOpenTarget {
        val existing = conversations.firstOrNull { it.otherUser.uid == friend.uid }
        val existingName = existing?.otherUser?.displayName?.takeIf { it.isNotBlank() }
        return DmOpenTarget(
            uid = friend.uid,
            displayName = existingName ?: friend.displayName,
            isExisting = existing != null,
        )
    }
}
