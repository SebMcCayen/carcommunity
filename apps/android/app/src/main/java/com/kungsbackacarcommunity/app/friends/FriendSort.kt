package com.kungsbackacarcommunity.app.friends

import java.text.Collator
import java.util.Locale

/**
 * How the established friends list is ordered on the Friends screen. Purely a
 * client-side view preference — the `friend-list` callable already returns every
 * edge with its `friendsSince` timestamp, so no sort ever hits the backend.
 */
enum class FriendSort {
    /** Case-insensitive, locale-aware (Swedish) by display name, A→Ö. */
    NAME,

    /** Most recently added first (`friendsSince` descending). */
    RECENTLY_ADDED,

    /**
     * Earliest added first (`friendsSince` ascending). This mirrors the order
     * `friend-list` itself returns, so it is the default and the list never
     * reorders on first load.
     */
    EARLIEST_ADDED,
}

/**
 * Pure, client-side ordering of the already-loaded friends list. No Firebase, no
 * suspension — unit-testable on a plain JVM.
 *
 * [FriendSort.NAME] uses a Swedish [Collator] so å/ä/ö sort AFTER z: Kotlin's own
 * `String` comparison and `lowercase()` are `Locale.ROOT` and would interleave
 * those letters with a/o. The comparison is case-insensitive because a collator
 * at [Collator.SECONDARY] strength ignores case while still ranking accents.
 *
 * The date sorts compare the raw ISO-8601 `friendsSince` strings lexicographically
 * — identical to how the backend orders them (manageFriends.ts) — which is exactly
 * chronological for a fixed ISO-8601 format.
 *
 * Rows missing the sort key (a null/blank display name, or a null/blank
 * `friendsSince`) always sort LAST, so a partially-populated row never jumps to
 * the top of any ordering. The sort is stable, so ties keep their incoming order.
 */
fun sortFriends(friends: List<FriendSummary>, sort: FriendSort): List<FriendSummary> =
    when (sort) {
        FriendSort.NAME -> {
            val collator = Collator.getInstance(Locale("sv", "SE")).apply {
                strength = Collator.SECONDARY
            }
            friends.sortedWith(
                compareBy<FriendSummary> { it.displayName.isNullOrBlank() }
                    .thenComparator { a, b ->
                        collator.compare(a.displayName.orEmpty(), b.displayName.orEmpty())
                    },
            )
        }

        FriendSort.RECENTLY_ADDED ->
            friends.sortedWith(
                compareBy<FriendSummary> { it.friendsSince.isNullOrBlank() }
                    .thenByDescending { it.friendsSince.orEmpty() },
            )

        FriendSort.EARLIEST_ADDED ->
            friends.sortedWith(
                compareBy<FriendSummary> { it.friendsSince.isNullOrBlank() }
                    .thenBy { it.friendsSince.orEmpty() },
            )
    }
