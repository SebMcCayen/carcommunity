package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.friends.FriendSummary
import java.text.Normalizer
import java.util.Locale

/** Matches convoy-core.ts MAX_CONVOY_INVITEES (create, not the invite-more limit). */
const val MAX_CREATE_CONVOY_INVITEES = 50

private fun searchName(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

/** Only ranks the current friend snapshot; usage never introduces additional candidates. */
fun rankedConvoyFriends(
    friends: List<FriendSummary>,
    successfulInvites: Map<String, Int>,
    query: String,
): List<FriendSummary> {
    val needle = searchName(query)
    return friends
        .filter { needle.isEmpty() || searchName(it.displayName.orEmpty()).contains(needle) }
        .sortedWith(
            compareByDescending<FriendSummary> { successfulInvites[it.uid]?.coerceAtLeast(0) ?: 0 }
                .thenBy { searchName(it.displayName.orEmpty()).ifEmpty { "\uffff" } }
                .thenBy { it.uid },
        )
}

/** Search is deliberately absent: hidden selected friends remain selected. */
fun createConvoySelection(selected: Set<String>, friends: List<FriendSummary>, viewerUid: String?): Set<String> =
    selected.intersect(friends.mapTo(HashSet()) { it.uid }) - setOfNotNull(viewerUid)
