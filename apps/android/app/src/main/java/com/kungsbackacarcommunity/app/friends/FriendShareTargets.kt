package com.kungsbackacarcommunity.app.friends

/**
 * Pure transform from a loaded friends snapshot to the friends a location can be
 * SHARED with: the established friends only (pending requests are not yet a
 * friend and cannot receive a DM), with any blank-uid row dropped (a malformed
 * edge would open a dead conversation), ordered by name for a scannable picker.
 *
 * Kept separate from the picker UI so the eligibility + ordering is unit-testable
 * on a plain JVM, and shared by every "pick a friend to share with" entry point
 * so they can never disagree on who is eligible.
 */
object FriendShareTargets {
    fun from(data: FriendsData): List<FriendSummary> =
        sortFriends(data.friends.filter { it.uid.isNotBlank() }, FriendSort.NAME)
}
