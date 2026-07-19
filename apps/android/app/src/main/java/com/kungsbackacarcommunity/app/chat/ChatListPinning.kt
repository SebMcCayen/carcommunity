package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * The one keyboard/scroll interaction shared by every chat surface — the group
 * channels ([com.kungsbackacarcommunity.app.chatchannels.ChannelChatContent]),
 * DM threads ([com.kungsbackacarcommunity.app.dm.ChatScreen]) and event chat
 * ([EventChatScreen]).
 *
 * All three pad their composer by `WindowInsets.ime.union(navigationBars)`, which
 * shrinks the message list's viewport from the bottom when the keyboard rises. A
 * `LazyColumn` holds its scroll OFFSET, not its bottom edge, so without a re-pin
 * the newest message slides out under the keyboard the moment the user taps the
 * input. Extracted here so the three surfaces cannot drift into three subtly
 * different scroll behaviours.
 */
object ChatListPinning {
    /**
     * Should the list jump back to the newest item now that the viewport shrank?
     *
     * Only for a reader who was already parked at the bottom: someone who has
     * scrolled up through history and then taps the composer must not be yanked
     * back down. "At the bottom" is the last item or the one before it, matching
     * the near-bottom tolerance the new-message auto-scroll already uses.
     *
     * A list that has not been laid out yet reports lastVisibleIndex -1, which
     * clears the threshold only for a SINGLE item (-1 >= 1 - 2). That is
     * deliberate: with one item the pin is a no-op, and beyond that nothing is
     * known about where the reader is, so the effect defers to the new-message
     * auto-scroll rather than guessing.
     *
     * @param lastVisibleIndex index of the last visible item, or -1 when the list
     *   has not been laid out yet.
     * @param totalItemsCount the LazyColumn's item count (messages plus any
     *   prepended "load older" header).
     */
    fun shouldRepinToNewest(lastVisibleIndex: Int, totalItemsCount: Int): Boolean {
        // Nothing to scroll to.
        if (totalItemsCount <= 0) return false
        return lastVisibleIndex >= totalItemsCount - 2
    }
}

/**
 * Is the soft keyboard up? Read as a plain inset rather than the experimental
 * `WindowInsets.isImeVisible`.
 *
 * `derivedStateOf` so the per-frame inset animation doesn't recompose the caller
 * 60 times a second — only the two transitions (up / down) propagate, which is
 * what makes [RepinToNewestOnImeRise] a rising-EDGE trigger rather than something
 * that fires continuously while the keyboard is open.
 */
@Composable
fun rememberImeVisible(): Boolean {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val visible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    return visible
}

/**
 * Re-pins [listState] to its newest item on the keyboard's RISING edge, subject
 * to [ChatListPinning.shouldRepinToNewest].
 *
 * Keyed on the boolean, so closing the keyboard — which grows the viewport back
 * on its own — moves nothing.
 */
@Composable
fun RepinToNewestOnImeRise(listState: LazyListState) {
    val imeVisible = rememberImeVisible()
    LaunchedEffect(imeVisible) {
        if (!imeVisible) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        if (ChatListPinning.shouldRepinToNewest(lastVisibleIndex, totalItems)) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
}
